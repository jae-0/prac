import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.machinezoo.sourceafis.FingerprintMatcher
import com.machinezoo.sourceafis.FingerprintTemplate
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import java.util.Base64

// API Gateway로부터 받을 요청 데이터 구조
data class FingerprintRequest(
    val studentId: String,    // 학생 ID
    val fingerprint: String   // Base64로 인코딩된 지문 이미지 데이터
)

/**
 * 지문 인증을 처리하는 Lambda 함수 핸들러
 * API Gateway로부터 요청을 받아 DynamoDB에 저장된 지문과 비교하여 결과를 반환
 */
class FingerprintHandler : RequestHandler<Map<String, Any>, Map<String, Any>> {
    private val objectMapper = jacksonObjectMapper()
    private val dynamoDbClient = DynamoDbClient.create()
    private val SIMILARITY_THRESHOLD = 40.0
    private val REQUIRED_MATCHES = 3

    override fun handleRequest(input: Map<String, Any>, context: Context): Map<String, Any> {
        try {
            // body에서 실제 데이터 추출
            val body = input["body"] as String
            // body를 FingerprintRequest로 변환
            val request = objectMapper.readValue<FingerprintRequest>(body)
            val verificationResult = verifyFingerprint(request)
            return createResponse(200, verificationResult)
        } catch (e: Exception) {
            context.logger.log("Error: ${e.message}")
            return createResponse(400, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private fun verifyFingerprint(request: FingerprintRequest): Map<String, Any> {
        // DynamoDB에서 저장된 지문 데이터 조회
        val getItemResponse = dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName("Fingerprint-db")
                .key(mapOf(
                    "studentId" to AttributeValue.builder().s(request.studentId).build()
                ))
                .build()
        )
    
        val item = getItemResponse.item() 
            ?: throw IllegalStateException("Student fingerprint data not found")
    
        // 입력받은 지문 이미지를 템플릿으로 변환
        val inputTemplate = try {
            val decodedInput = Base64.getDecoder().decode(request.fingerprint)
            FingerprintTemplate().dpi(500.0).create(decodedInput)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid fingerprint data")
        }
    
        // 저장된 지문들과 비교하여 점수 계산
        val scores = mutableListOf<Double>()
        val images = item["Images"]?.m() ?: throw IllegalStateException("Invalid stored fingerprint data")
        
        images.forEach { (_, value) ->
            try {
                val storedTemplate = FingerprintTemplate()
                    .dpi(500.0)
                    .create(Base64.getDecoder().decode(value.s()))
                
                val score = FingerprintMatcher()
                    .index(storedTemplate)
                    .match(inputTemplate)
                
                scores.add(score)
            } catch (e: Exception) {
                throw IllegalStateException("Error comparing fingerprint: ${e.message}")
            }
        }
    
        // 저장된 지문의 개수가 3개가 아닐 경우 에러
        if (scores.size != 3) {
            throw IllegalStateException("Invalid number of stored fingerprints")
        }
    
        // 평균 점수 계산
        val averageScore = scores.average()
        
        // 평균 점수가 임계값을 넘는지 확인
        return if (averageScore >= SIMILARITY_THRESHOLD) {
            mapOf(
                "verified" to true,
                "scores" to scores,
                "averageScore" to averageScore,
                "message" to "Fingerprint verification successful with average score: $averageScore"
            )
        } else {
            throw IllegalStateException("Fingerprint verification failed: Average score ($averageScore) below threshold")
        }
    }

    /**
     * API Gateway 응답 형식에 맞춰 결과 생성
     * @param statusCode HTTP 상태 코드
     * @param body 응답 본문
     * @return API Gateway 형식의 응답 Map
     */
    private fun createResponse(statusCode: Int, body: Map<String, Any>): Map<String, Any> {
        return mapOf(
            "statusCode" to statusCode,
            "headers" to mapOf(
                "Content-Type" to "application/json",
                "Access-Control-Allow-Origin" to "*"
            ),
            "body" to objectMapper.writeValueAsString(body)
        )
    }
}
