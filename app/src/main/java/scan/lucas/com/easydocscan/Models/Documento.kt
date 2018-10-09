package scan.lucas.com.easydocscan.Models

import android.graphics.Bitmap
import java.util.*

class Documento(
        var AWSKey: String? ,
        var Nome: String,
        var DataCriacao: Date,
        var DataModificacao: Date,
        var Tamanho: Long,
        var Paginas: Int,
        var Thumbnail: Bitmap? = null){

    init {
        this.Nome = ""
        this.DataCriacao = Date()
        this.DataModificacao = Date()
        this.Paginas = 0
    }
    constructor(Nome: String, DataCriacao: Date, DataModificacao: Date, Paginas: Int)
            : this("", Nome, DataCriacao, DataModificacao, 0, Paginas, null)
}