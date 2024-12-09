import com.machinezoo.sourceafis.FingerprintMatcher
import com.machinezoo.sourceafis.FingerprintTemplate
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.Base64
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Scanner

data class FingerprintData(
    val studentId: String,
    val fingerprint: String
)


class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            /**** 여기에 DB 객체 받아오도록 설정 필요 ****/
            val apiGatewayEvent = """
                {
                    "studentId": "exampleStudentId",
                    "fingerprint": "base64EncodedFingerprintData"
                }
            """.trimIndent()

            // JSON 데이터를 데이터 클래스로 변환
            val objectMapper = jacksonObjectMapper()
            val requestData: FingerprintData = objectMapper.readValue(apiGatewayEvent, FingerprintData::class.java)
            // DynamoDB 클라이언트 생성
            val dynamoDbClient = DynamoDbClient.create()

             // 가져올 테이블 이름과 키 값 설정
            val tableName = "Fingerprint-db"
            val primaryKeyName = requestData.studentId
            val primaryKeyValue = requestData.fingerprint

            /// GetItem 요청 생성
            val getItemRequest = GetItemRequest.builder()
                .tableName(tableName)
                .key(
                    mapOf(
                        "studentId" to AttributeValue.builder().s(primaryKeyName).build()
                    )
                )
                .build()

            // GetItem 요청 실행
            val getItemResponse = dynamoDbClient.getItem(getItemRequest)
            val item = getItemResponse.item()

            if (item != null) {
                // Images 속성 가져오기
                val images = item["Images"]?.m() ?: emptyMap()

                // 비교할 이미지 decode해서 비교할 준비
                val comparedecodedImage = Base64.getDecoder().decode(primaryKeyValue)
                val comparededecodedImage = FingerprintTemplate().dpi(500.0).create(comparedecodedImage)
        
                // 2. DynamoDB 이미지 데이터 디코딩 및 배열에 추가
                images.forEach { (_, value) ->
                    val base64Data = value.s()
                    val decodedImage = Base64.getDecoder().decode(base64Data)
                    val dedecodedImage = FingerprintTemplate().dpi(500.0).create(decodedImage)

                    val score = FingerprintMatcher().index(dedecodedImage).match(comparededecodedImage)

                    if (score < 40){
                        //에러 발생!!!!!
                    }
                }
            }

            else {
                println("No item found for studentId ${primaryKeyName}")
            }
            ///////////////////////////////////////////
        }
    }
}
