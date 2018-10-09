package scan.lucas.com.easydocscan

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.shapes.PathShape
import android.media.ThumbnailUtils
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import kotlinx.android.synthetic.main.activity_scan.*
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import scan.lucas.com.easydocscan.Enum.TipoProcessamento
import scan.lucas.com.easydocscan.Models.RequisicaoProcessamento
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

class Processamento(looper: Looper, context: android.content.Context) : Handler(looper) {
    var processando: Boolean = false
    var aguardFocus: Boolean = false
    val mContext: android.content.Context
    var QuantidadeImagens: Int = 0
    var pontosPreview: List<Point> = ArrayList<Point>()

    init {
        this.mContext = context

    }

    override fun handleMessage(msg: Message) {
        super.handleMessage(msg)

        if (msg.obj.javaClass != RequisicaoProcessamento::class.java)
            return

        var result = msg.obj as RequisicaoProcessamento

        when (result.tipo) {

            TipoProcessamento.PICTURE -> {
                val fotoOriginal = result.obj as Mat

                var fotoCopia = Mat()
                fotoOriginal.copyTo(fotoCopia)

                val pontos = detectarDccumento(fotoOriginal)
                //caso detecte os pontos recorta a imagem
                if (pontos != null && pontos.count() == 4) {
                    fotoCopia = cortarImagem(fotoOriginal, pontos)
                } else if (pontosPreview != null && pontosPreview.count() == 4) {
                    fotoCopia = cortarImagem(fotoOriginal, pontosPreview)
                }
                var imagemSemFiltro = Mat()
                fotoCopia.copyTo(imagemSemFiltro)

                Imgproc.cvtColor(fotoCopia, fotoCopia, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.adaptiveThreshold(fotoCopia, fotoCopia, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 15, 15.0)

                //passa a matriz original,
                //a recortada sem o processamento
                //e a recortada com o processamento
                salvarDocumento(fotoOriginal, imagemSemFiltro, fotoCopia)

                val canvasCustom = (mContext as Activity).canvasCustom
                canvasCustom.clear()
                mContext.runOnUiThread {

                    canvasCustom.invalidate()
                }
                aguardFocus = false
                processando = false

                fotoOriginal.release()
                fotoCopia.release()
                imagemSemFiltro.release()
            }
            TipoProcessamento.PREVIEW -> {
                val pt = detectarDccumento(result.obj as Mat)
                if (pt != null && pt.size == 4) {
                    pontosPreview = pt
                    var s = (result.obj as Mat).size()

                    val ratio = (s!!.height / 500.toDouble())
                    var pontosNovos = ArrayList<Point>()
                    for (p in pt) {
                        val x = (p.x * ratio).toInt()
                        val y = (p.y * ratio).toInt()
                        pontosNovos.add(Point(x.toDouble(), y.toDouble()))
                    }
                    val tamanhoTela = android.util.Size(s.width.toInt(), s.height.toInt())
                    desenhar(tamanhoTela, pontosNovos)
                }
                processando = false
            }
        }
    }

    fun salvarDocumento(fotoOriginal: Mat, imagemSemFiltro: Mat, fotoCopia: Mat) {
        val file = File(caminhoPadrao)
        if (!file.exists())
            file.mkdirs()

        Imgcodecs.imwrite("${file.absolutePath}/croped$QuantidadeImagens.jpg", imagemSemFiltro)
        Imgcodecs.imwrite("${file.absolutePath}/processado$QuantidadeImagens.jpg", fotoCopia)
        Imgcodecs.imwrite("${file.absolutePath}/original$QuantidadeImagens.jpg", fotoOriginal)

        var imageBitmap = ThumbnailUtils.extractThumbnail(
                BitmapFactory.decodeFile("${file.absolutePath}/processado$QuantidadeImagens.jpg"), 50, 50)
        QuantidadeImagens++

        (mContext as Activity).runOnUiThread {
            if (mContext.image_layout.visibility == android.view.View.GONE) {
                mContext.image_layout.visibility = View.VISIBLE
                mContext.imagePreview.visibility = View.VISIBLE
            }
            mContext.qntImage.text = QuantidadeImagens.toString()
            mContext.imagePreview.setImageBitmap(imageBitmap)

        }
    }

    public fun detectarDccumento(imagem: Mat): List<Point> {
        var tamanhoNovo = obterNovoTamanho(imagem.size().width, imagem.size().height)
        var imagemRedimencionada = redimencionarImagem(imagem, tamanhoNovo)

        //libera a imagem original da memoria
        //imagem.release()

        var imagemCinza = converterParaCinza(imagemRedimencionada, tamanhoNovo, true)

        //embaça a imagem de proposito para excluir conteudo interno
        Imgproc.medianBlur(imagemCinza, imagemCinza, 9)

        var imgCanny = algoritimoCanny(imagemCinza, salvarArquivo = true)

        var contornos = retornaPontosContornos(imgCanny)

        //libera as imagens processadas da memoria
        imagemRedimencionada.release()
        imagemCinza.release()
        imgCanny.release()

        return contornos
    }

    fun retornaPontosContornos(imagemCanny: Mat): List<Point> {
        val contornos = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        Imgproc.findContours(imagemCanny, contornos, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        hierarchy.release()
        //reordena os contornos por area
        Collections.sort(contornos) { lhs, rhs -> java.lang.Double.valueOf(Imgproc.contourArea(rhs)).compareTo(Imgproc.contourArea(lhs)) }

        return melhoresContornos(contornos)
    }

    fun melhoresContornos(contornos: ArrayList<MatOfPoint>): List<Point> {
        var indexArea: Int = 0
        for (c in contornos) {

            // convertemos de MatOfPoint (int) para MatOfPoint2f (float)
            val c2f = MatOfPoint2f(*c.toArray())

            //Calcula um perímetro de contorno ou um comprimento de curva.
            val peri = Imgproc.arcLength(c2f, true)

            //Aproxima os contornos com a precisão especificada.
            val approxPolyDP = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approxPolyDP, 0.02 * peri, true)

            val pontosList = approxPolyDP.toList()

            val app = MatOfPoint()
            approxPolyDP.convertTo(app, CvType.CV_32S)
            //se a aproximação dos contornos resultar em 4 pontos,
            // podemos assuimir que é um poligono de 4 lados
            //Verifica tambem se é convexo ou não
            if (pontosList.size == 4 &&
                    Math.abs(Imgproc.contourArea(contornos.get(indexArea))) > 3000 &&
                    Imgproc.isContourConvex(app)) {
                val point = melhoresPontos(pontosList)
                return point
            }
        }

        return ArrayList<Point>()
    }

    fun melhoresPontos(src: List<Point>): MutableList<Point> {
        val result = ArrayList<Point>(4)

        val comparacaoSoma = Comparator<Point> { lhs, rhs -> java.lang.Double.valueOf(lhs.y + lhs.x).compareTo(rhs.y + rhs.x) }

        val comparacaoDiferenca = Comparator<Point> { lhs, rhs -> java.lang.Double.valueOf(lhs.y - lhs.x).compareTo(rhs.y - rhs.x) }

        //ponto superior esquerdo = soma minima dos pontos
        result.add(Collections.min(src, comparacaoSoma))

        //ponto superior direito = diferenca minina dos pontos
        result.add(Collections.min(src, comparacaoDiferenca))

        //ponto inferior direito = maxima soma dos pontos
        result.add(Collections.max(src, comparacaoSoma))

        //ponto inferior esquerdo = maxima diferenca dos pontos
        result.add(Collections.max(src, comparacaoDiferenca))

        return result
    }

    fun aplicarThreshold(mat: Mat, size: Size, salvarArquivo: Boolean = false) {
        try {
            var threshedImage = Mat(size, CvType.CV_8UC4)
            Imgproc.adaptiveThreshold(mat, mat, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 15, 15.0)
            if (salvarArquivo)
                Imgcodecs.imwrite("$caminhoPadrao/threshed.jpg", threshedImage)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar Threshold: ${e.message}")
        }
    }

    fun converterParaCinza(mat: Mat, size: Size, salvarArquivo: Boolean = false): Mat {
        try {
            var imagemCinza = Mat(size, CvType.CV_8UC4)
            Imgproc.cvtColor(mat, imagemCinza, Imgproc.COLOR_RGBA2GRAY, 4)
            if (salvarArquivo)
                Imgcodecs.imwrite("$caminhoPadrao/grayImage.jpg", imagemCinza)
            return imagemCinza
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao converter para cinza: ${e.message}")
            return Mat()
        }
    }

    fun redimencionarImagem(mat: Mat, size: Size): Mat {
        var resizedImage = Mat(size, CvType.CV_8UC4)
        Imgproc.resize(mat, resizedImage, size)
        return resizedImage
    }

    fun obterNovoTamanho(width: Double, height: Double): Size {
        val ratio = height / 500
        val height = (height.toDouble() / ratio.toDouble()).toInt()
        val width = (width.toDouble() / ratio.toDouble()).toInt()
        return Size(width.toDouble(), height.toDouble())
    }

    fun algoritimoCanny(mat: Mat, threshold1: Double = 75.0, threshold2: Double = 200.0, salvarArquivo: Boolean): Mat {
        try {
            var cannedImage = Mat(mat.size(), CvType.CV_8UC1)

            Imgproc.Canny(mat, cannedImage, threshold1, threshold1)
            if (salvarArquivo)
                Imgcodecs.imwrite("$caminhoPadrao/cannedImage.jpg", cannedImage)
            return cannedImage
        } catch (e: Exception) {
            Log.e(TAG, e.message)
            return Mat()
        }
    }

    fun angle(pt1: Point, pt2: Point, pt0: Point): Double {
        val dx1 = pt1.x - pt0.x
        val dy1 = pt1.y - pt0.y
        val dx2 = pt2.x - pt0.x
        val dy2 = pt2.y - pt0.y
        return (dx1 * dx2 + dy1 * dy2) / Math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)
    }

    private fun desenhar(size: android.util.Size?, points: List<Point>) {
        var path = Path()

        var p1x = points[0].x.toFloat()
        var p1y = points[0].y.toFloat()

        var p2x = points[1].x.toFloat()
        var p2y = points[1].y.toFloat()

        var p3x = points[3].x.toFloat()
        var p3y = points[3].y.toFloat()

        var p4x = points[2].x.toFloat()
        var p4y = points[2].y.toFloat()

        path.moveTo(p1x, p1y)
        path.lineTo(p2x, p2y)
        path.lineTo(p4x, p4y)
        path.lineTo(p3x, p3y)
        path.close()

        val formatoDocumento = PathShape(path, size!!.width.toFloat(), size.height.toFloat())

        val conteudo = Paint()
        conteudo.color = Color.argb(66, 41, 128, 185)

        val borda = Paint()
        borda.color = Color.rgb(41, 128, 185)
        borda.strokeWidth = 4.5f

        val canvasCustom = (mContext as Activity).canvasCustom
        canvasCustom.clear()
        canvasCustom.add(formatoDocumento, conteudo, borda)

        mContext.runOnUiThread {

            canvasCustom.invalidate()
        }
    }

    companion object {
        val TAG = "ProcessamentoOpenCV"
        val caminhoPadrao = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath}/Scan"
        fun cortarImagem(imagemEntrada: Mat, pontos: List<Point>, ratio: Float = 500.0f): Mat {
            val ratio = imagemEntrada.size().height / ratio

            val topL = pontos.get(0)
            val topR = pontos.get(1)
            val botR = pontos.get(2)
            val botL = pontos.get(3)

            //calculamos a distancia euclidiana entre os 4 pontos para obter a maior distancia
            val widthA = Math.sqrt(Math.pow(botR.x - botL.x, 2.0) + Math.pow(botR.y - botL.y, 2.0))
            val widthB = Math.sqrt(Math.pow(topR.x - topL.x, 2.0) + Math.pow(topR.y - topL.y, 2.0))
            var maiorWidth = (Math.max(widthA, widthB) * ratio)

            val heightA = Math.sqrt(Math.pow(topR.x - botR.x, 2.0) + Math.pow(topR.y - botR.y, 2.0))
            val heightB = Math.sqrt(Math.pow(topL.x - botL.x, 2.0) + Math.pow(topL.y - botL.y, 2.0))
            var maiorHeight = (Math.max(heightA, heightB) * ratio)

            val origem = Mat(4, 1, CvType.CV_32FC2)
            val destino = Mat(4, 1, CvType.CV_32FC2)

            origem.put(0, 0,
                    topL.x * ratio,
                    topL.y * ratio,
                    topR.x * ratio,
                    topR.y * ratio,
                    botR.x * ratio,
                    botR.y * ratio,
                    botL.x * ratio,
                    botL.y * ratio)
            destino.put(0, 0, 0.0, 0.0, maiorWidth, 0.0, maiorWidth, maiorHeight, 0.0, maiorHeight)
            val docFinal = Mat(maiorHeight.toInt(), maiorWidth.toInt(), CvType.CV_8UC4)

            val matrizPespectiva = Imgproc.getPerspectiveTransform(origem, destino)

            Imgproc.warpPerspective(imagemEntrada, docFinal, matrizPespectiva, docFinal.size())

            return docFinal
        }

        fun detectarPontosImagem(con: Context, mat: Mat): List<Point>{
            return Processamento(Looper.getMainLooper(),con).detectarDccumento(mat)
        }
    }
}