package scan.lucas.com.easydocscan.Models
import java.util.Date

class DocumentModel(val docid: String,
                    val awskey: String,
                    val name: String,
                    val data_modificacao: Date,
                    val tamanho: Int,
                    val paginas: Int)
