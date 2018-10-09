package scan.lucas.com.easydocscan

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.SearchManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.SearchView
import android.support.v7.widget.StaggeredGridLayoutManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.services.s3.internal.S3HttpUtils
import com.amazonaws.services.s3.model.ObjectMetadata
import com.itextpdf.text.pdf.PdfReader
import io.realm.Realm
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import scan.lucas.com.easydocscan.Adapters.PdfRealmRVAdapter
import scan.lucas.com.easydocscan.DAL.DocumentoRealm
import scan.lucas.com.easydocscan.Enum.OrderBy
import scan.lucas.com.easydocscan.Enum.TipoListagem
import scan.lucas.com.easydocscan.Enum.TipoNotificacao
import scan.lucas.com.easydocscan.Helpers.PreferenceHelper
import scan.lucas.com.easydocscan.Helpers.PreferenceHelper.get
import scan.lucas.com.easydocscan.Helpers.PreferenceHelper.set
import scan.lucas.com.easydocscan.Interfaces.IPopupMenuListener
import scan.lucas.com.easydocscan.Models.Documento
import scan.lucas.com.easydocscan.Utils.NotificationManagerCustom
import scan.lucas.com.easydocscan.Utils.ToByteArray
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SearchView.OnQueryTextListener, IPopupMenuListener {

    private lateinit var docAdapterRealm: PdfRealmRVAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var amazonS3: AmazonS3Conection
    private lateinit var realm: Realm
    private var docNovo: Documento? = null

    var mFazerUpload = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar.setTitleTextColor(resources.getColor(R.color.white))

        VerificarPermissao()
        prefs = PreferenceHelper.defaultPrefs(this)
        amazonS3 = AmazonS3Conection("osas", KEY, SECRET)

        swipeRefreshLayout.setOnRefreshListener(SwipeRefreshLayout.OnRefreshListener {
            loading.visibility = View.GONE
            AtualizarDocs()
        })

        realm = Realm.getDefaultInstance()
        popularDB()

        val data = realm.where(DocumentoRealm::class.java).sort("name").findAll()
        rvDocumentos.layoutManager = LinearLayoutManager(this@MainActivity)
        docAdapterRealm = PdfRealmRVAdapter(TipoListagem.LIST, realm, data, this)
        rvDocumentos.adapter = docAdapterRealm

        mFazerUpload = intent.getBooleanExtra("uploadFile", false)



        if (mFazerUpload) {
            val fileName = intent.getStringExtra("fileName")

            UploadArquivo(fileName, intent.data)

        }

        novoDoc.setOnClickListener {
            val intent = Intent(this@MainActivity, ScanActivity::class.java)
            startActivity(intent)
        }
    }
    private fun popularDB() {
        val firstStart: Boolean? = prefs["FIRSTSTART", true]
        if (firstStart == null || firstStart == true) {
            prefs["FIRSTSTART"] = false
            realm.executeTransactionAsync {
                val currentIdNum = it.where(DocumentoRealm::class.java)?.max("docid")
                var nextId: Int
                if (currentIdNum == null) {
                    nextId = 1
                } else {
                    nextId = currentIdNum.toInt() + 1
                }

                var docs = AmazonS3Conection("osas", "AKIAJOKSTNOS5AIEMKXQ", "YKUwRuLe9zpETsIF6bhfee9h3YZEekoKSmwImSiq")
                        .listarArquivos(OrderBy.NomeAsc)

                var docInseir: MutableList<DocumentoRealm> = ArrayList()

                for (d in docs) {
                    var doc = DocumentoRealm()
                    doc.tamanho = d.size
                    doc.data_modificacao = d.lastModified
                    doc.name = S3HttpUtils.urlDecode(d.key).removePrefix("LMELO/").removeSuffix(".pdf")
                    doc.awskey = d.key
                    doc.docid = nextId
                    nextId++
                    docInseir.add(doc)
                }

                it.insertOrUpdate(docInseir)
                runOnUiThread {
                    swipeRefreshLayout.clearAnimation()
                    loading.isIndeterminate = false
                    loading.visibility = View.GONE
                }
            }
        } else {
            loading.isIndeterminate = false
            loading.visibility = View.GONE
        }
    }
    private fun deletarArquivo(menuItem: MenuItem,adapterPosition: Int){
        AlertDialog.Builder(this@MainActivity)
                .setMessage("Deseja excluir o documento?")
                .setTitle("Excluir documento")
                .setCancelable(true)
                .setNegativeButton("Não", DialogInterface.OnClickListener { dialog: DialogInterface, which: Int ->

                })
                .setPositiveButton("Sim", DialogInterface.OnClickListener { dialog, which ->
                    try {
                        var key = docAdapterRealm.data!![adapterPosition].awskey
                        if (realm == null)
                            realm = Realm.getDefaultInstance()

                        realm.executeTransaction { realm ->
                            val objExistente = realm.where(DocumentoRealm::class.java).equalTo("awskey", key).findFirst()
                            if (objExistente != null) {
                                objExistente.deleteFromRealm()
                            }

                            docAdapterRealm.notifyItemRemoved(adapterPosition);

                            amazonS3.deletarArquivo(key!!)
                        }
                    } catch (e: Exception) {

                    }
                })
                .create()
                .show()
    }
    private fun baixarArquivo(menuItem: MenuItem, adapterPosition: Int) {
        var key = docAdapterRealm.data!![adapterPosition].awskey
        var nome = docAdapterRealm.data!![adapterPosition].name
        if (key != null) {
            var toast = Toast.makeText(this@MainActivity, "Seu arquivo será baixado em instantes...", Toast.LENGTH_LONG)
            toast.show()
            var notifiDownload = NotificationManagerCustom(nome!!, this.applicationContext, TipoNotificacao.DOWNLOAD)
            var tmpFile = File.createTempFile("filetemp", ".pdf")
            amazonS3.downloadArquivo(key, this@MainActivity, tmpFile, object : TransferListener {

                override fun onStateChanged(id: Int, state: TransferState) {
                    if (TransferState.COMPLETED == state) {

                        var newFile = tmpFile.copyTo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/$nome.pdf"), true)

                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(Uri.fromFile(newFile), "application/pdf")
                        intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
                        val intent1 = Intent.createChooser(intent, "Open With")
                        val pendingIntent = PendingIntent.getActivity(this@MainActivity, 0, intent1, 0)

                        notifiDownload.updateNotification(100, nome, getString(R.string.app_name), pendingIntent)
                        toast.cancel()
                        toast.duration = Toast.LENGTH_SHORT
                        toast.setText("Download Comcluido")
                        toast.show()
                    }
                }

                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                    val percentDonef = bytesCurrent.toFloat() / bytesTotal.toFloat() * 100
                    val percentDone = percentDonef.toInt()
                    notifiDownload.updateNotification(percentDone, nome, getString(R.string.app_name))
                }

                override fun onError(id: Int, ex: Exception) {
                    notifiDownload.deleteNotification()
                    AlertDialog.Builder(this@MainActivity)
                            .setMessage("Não foi possivel baixar o arquivo $nome, tente novamente mais tarde")
                            .setTitle("Erro")
                            .setCancelable(true)
                            .setPositiveButton("Ok", DialogInterface.OnClickListener { dialog, which ->
                            })
                            .create()
                            .show()
                }
            })
        }
    }

    private fun visualizarArquivo(menuItem: MenuItem, adapterPosition: Int) {
        var key = docAdapterRealm.data!![adapterPosition].awskey
        var nome = docAdapterRealm.data!![adapterPosition].name
        if (key != null) {
            var notifiDownload = NotificationManagerCustom(nome!!, this.applicationContext, TipoNotificacao.DOWNLOAD)
            var tmpFile = File.createTempFile("filetemp", ".pdf")
            amazonS3.downloadArquivo(key, this@MainActivity, tmpFile, object : TransferListener {

                override fun onStateChanged(id: Int, state: TransferState) {
                    if (TransferState.COMPLETED == state) {
                        notifiDownload.deleteNotification()
                        var newFile = tmpFile.copyTo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/$nome.pdf"), true)
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(Uri.fromFile(newFile), "application/pdf")
                        intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
                        val intent1 = Intent.createChooser(intent, "Open With")
                        try {
                            startActivity(intent1)
                        } catch (e: ActivityNotFoundException) {
                            // Instruct the user to install a PDF reader here, or something
                        }// Handle a completed download.
                    }
                }

                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                    val percentDonef = bytesCurrent.toFloat() / bytesTotal.toFloat() * 100
                    val percentDone = percentDonef.toInt()
                    notifiDownload.updateNotification(percentDone, nome, getString(R.string.app_name))
                }

                override fun onError(id: Int, ex: Exception) {
                    notifiDownload.deleteNotification()
                    AlertDialog.Builder(this@MainActivity)
                            .setMessage("Não foi possivel baixar o arquivo $nome, tente novamente mais tarde")
                            .setTitle("Erro")
                            .setCancelable(true)
                            .setPositiveButton("Ok", DialogInterface.OnClickListener { dialog, which ->
                            })
                            .create()
                            .show()
                }
            })
        }
    }

    private fun UploadArquivo(fileName: String, uri: Uri) {

        var notifiUpload = NotificationManagerCustom(fileName, this.applicationContext, TipoNotificacao.UPLOAD)
        val doc = DocumentoRealm()
        val transferUtility = amazonS3.getTransferUtility(this.applicationContext)
        val objectMetadata = ObjectMetadata()

        val pdfC = com.shockwave.pdfium.PdfiumCore(this.applicationContext)
        var thumbnail = ""
        try {
            val pdfDocument = pdfC.newDocument(ParcelFileDescriptor.open(File(uri.path), ParcelFileDescriptor.MODE_READ_WRITE))
            pdfC.openPage(pdfDocument, 0)

            val width = pdfC.getPageWidthPoint(pdfDocument, 0)
            val height = pdfC.getPageHeightPoint(pdfDocument, 0)

            // ARGB_8888 - best quality, high memory usage, higher possibility of OutOfMemoryError
            // RGB_565 - little worse quality, twice less memory usage
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            pdfC.renderPageBitmap(pdfDocument, bitmap, 0, 0, 0, width, height)

            val pdfReader = PdfReader(uri.path)
            doc.name = fileName
            doc.data_modificacao = Date()
            doc.paginas = pdfReader.numberOfPages
            doc.awskey = "LMELO/$fileName"
            doc.tamanho = pdfReader.fileLength
            doc.thumbnail = bitmap.ToByteArray()

            pdfReader.close()
            pdfC.closeDocument(pdfDocument) // important!
        } catch (ex: IOException) {
            Log.e(TAG, ex.message)
            ex.printStackTrace()
        }
        objectMetadata.addUserMetadata("pages", doc.paginas.toString())
        objectMetadata.addUserMetadata("data_criacao", (DateFormat.getDateInstance(DateFormat.FULL).format(doc.data_modificacao)))

        //grava os dados no banco
        try {
            if (realm == null)
                realm = Realm.getDefaultInstance()

            realm.executeTransaction { realm ->
                val objExistente = realm.where(DocumentoRealm::class.java).equalTo("awskey", doc.awskey).findFirst()
                if (objExistente != null) {
                    doc.docid = objExistente.docid
                    realm.insertOrUpdate(doc)
                } else {
                    val currentIdNum = realm.where(DocumentoRealm::class.java)?.max("docid")
                    var nextId: Int
                    if (currentIdNum == null) {
                        nextId = 1
                    } else {
                        nextId = currentIdNum.toInt() + 1
                    }
                    doc.docid = nextId
                    realm.insertOrUpdate(doc)
                }


            }
        } catch (e: Exception) {

        }



        //faz o upload para o s3
        amazonS3.uploadArquivo(doc.awskey.toString(), this@MainActivity, File(uri.path),objectMetadata, object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState?) {
                if (TransferState.COMPLETED == state) {
                    notifiUpload.deleteNotification()
                    mFazerUpload = false
                }

            }

            override fun onError(id: Int, ex: java.lang.Exception?) {
                mFazerUpload = false
                notifiUpload.deleteNotification()
                AlertDialog.Builder(this@MainActivity)
                        .setMessage("Não foi possivel enviar o arquivo $fileName para o AWS S3")
                        .setTitle("Erro")
                        .setCancelable(true)
                        .setPositiveButton("Tentar Novamente", DialogInterface.OnClickListener { dialog, which ->
                            UploadArquivo(fileName, uri)
                        })
                        .create()
                        .show()
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                val percentDonef = bytesCurrent.toFloat() / bytesTotal.toFloat() * 100
                val percentDone = percentDonef.toInt()
                notifiUpload.updateNotification(percentDone, fileName, getString(R.string.app_name))
            }

        })

    }
    private fun AtualizarDocs() {
        try {


        } catch (e: Exception) {
            Log.e(TAG, e.message)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Ocorreu um erro ao listar os arquivos. Tente novamnete mais tarde", Toast.LENGTH_LONG).show()
            }
        } finally {
            runOnUiThread {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }
    override fun onPopupMenuClicked(menuItem: MenuItem, adapterPosition: Int) {
        when (menuItem.itemId) {
            R.id.menu_baixar -> {
                baixarArquivo(menuItem, adapterPosition)
            }
            R.id.menu_visualizar -> {
                visualizarArquivo(menuItem, adapterPosition)
            }
            R.id.menu_excluir ->{
                deletarArquivo(menuItem,adapterPosition)
            }

        }
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        var searchItem = menu.findItem(R.id.action_search)

        val searchManager = this@MainActivity.getSystemService(Context.SEARCH_SERVICE) as SearchManager

        var searchView: SearchView? = null
        if (searchItem != null) {
            searchView = searchItem.actionView as SearchView
        }
        if (searchView != null) {
            searchView.setSearchableInfo(searchManager.getSearchableInfo(this@MainActivity.componentName))
            searchView.setOnQueryTextListener(this)
        }

        return true
    }
    override fun onQueryTextChange(text: String): Boolean {
        try {
            docAdapterRealm.resultadoFiltro(text)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        try {
            if (!query.isNullOrEmpty())
                docAdapterRealm.resultadoFiltro(query!!)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        when (item.itemId) {
            R.id.menuLista -> {
                if (item.title == "Visualizar como Lista") {
                    item.title = "Visualizar como Grid"
                    item.icon = resources.getDrawable(R.drawable.ic_grid)
                    rvDocumentos.layoutManager = LinearLayoutManager(this)
                    docAdapterRealm = PdfRealmRVAdapter(TipoListagem.LIST, realm, realm.where(DocumentoRealm::class.java).findAll(), this)
                    rvDocumentos.adapter = docAdapterRealm
                } else {
                    item.title = "Visualizar como Lista"
                    item.icon = resources.getDrawable(R.drawable.ic_list)
                    rvDocumentos.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                    docAdapterRealm = PdfRealmRVAdapter(TipoListagem.GRID, realm, realm.where(DocumentoRealm::class.java).findAll(), this)
                    rvDocumentos.setHasFixedSize(true)
                    rvDocumentos.adapter = docAdapterRealm
                }
                return true

            }
            R.id.menuOrderData -> {
                val order: String? = prefs["ORDERITENS_DATA", "ASC"]
                if (order!! == "ASC") {
                    docAdapterRealm.OrderByData(OrderBy.DataAsc)
                    prefs["ORDERITENS_DATA"] = "DESC"
                } else {
                    docAdapterRealm.OrderByData(OrderBy.DataDesc)
                    prefs["ORDERITENS_DATA"] = "ASC"
                }

                return true
            }
            R.id.menuOrderNome -> {
                val order: String? = prefs["ORDERITENS_NOME", "ASC"]
                if (order!! == "ASC") {
                    docAdapterRealm.OrderByNome(OrderBy.DataAsc)
                    prefs["ORDERITENS_NOME"] = "DESC"
                } else {
                    docAdapterRealm.OrderByNome(OrderBy.DataDesc)
                    prefs["ORDERITENS_NOME"] = "ASC"
                }
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {

    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_INTERNET_PERMISSION -> {

                // If request is cancelled, the result arrays are empty.
                if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    //CarregarItens()
                } else {

                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Toast.makeText(this@MainActivity, "Permissão negada para acessar a Internet", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }// other 'case' lines to check for other
        // permissions this app might request
    }
    private fun VerificarPermissao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var hasPermission = checkSelfPermission(Manifest.permission.INTERNET)
            hasPermission = checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            if (hasPermission != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE),
                        REQUEST_INTERNET_PERMISSION)
                return
            }

        }
    }
    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {

        private val REQUEST_INTERNET_PERMISSION = 3

        private val TAG = "MainActivity"
        private val KEY = "AKIAJOKSTNOS5AIEMKXQ"
        private val SECRET = "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"
        // Used to load the 'native-lib' library on application startup.
        init {
            System.loadLibrary("native-lib")
        }
    }
}
