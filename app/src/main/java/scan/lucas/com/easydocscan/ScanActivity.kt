package scan.lucas.com.easydocscan


import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Camera
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import scan.lucas.com.easydocscan.Enum.TipoProcessamento
import scan.lucas.com.easydocscan.Models.RequisicaoProcessamento
import scan.lucas.com.easydocscan.Utils.CompararAreaSizes
import scan.lucas.com.easydocscan.Helpers.PreferenceHelper
import scan.lucas.com.easydocscan.Helpers.PreferenceHelper.get
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlinx.android.synthetic.main.activity_scan.*


//import scan.lucas.com.easydocscan.models.ImageMessage

class ScanActivity : AppCompatActivity(),
        SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {

    private var mPreferencias: SharedPreferences? = null

    private var mProcessamento: Processamento? = null
    private var mBackgroundThread: HandlerThread? = null
    private var mSurfaceHolder: SurfaceHolder? = null
    lateinit var mCamera: android.hardware.Camera

    private var mAutoFocus: Boolean = false
    private var mCameraFlash: Boolean = false
    private var travarObturador: Boolean = false
    val permissoes: kotlin.Array<String> = arrayOf(Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        btnCancelar.setOnClickListener({
            AlertDialog.Builder(this@ScanActivity)
                    .setMessage("Deseja cancelar a digitalização?")
                    .setTitle("Sair")
                    .setCancelable(true)
                    .setPositiveButton("Não", DialogInterface.OnClickListener { dialog: DialogInterface, which: Int ->

                    })
                    .setPositiveButton("Sim", DialogInterface.OnClickListener { dialog, which ->
                        val intent = Intent(this@ScanActivity, MainActivity::class.java)
                        intent.putExtra("uploadFile", false)
                        startActivity(intent)
                    })
                    .create()
                    .show()
        })
        btnflash.setOnClickListener(View.OnClickListener {
            mCameraFlash = ativarFlash(!mCameraFlash)
            if (mCameraFlash) {
                this.runOnUiThread {
                    btnflash.setBackgroundResource(R.drawable.ic_flash_on)
                }
            } else
                this.runOnUiThread {
                    btnflash.setBackgroundResource(R.drawable.ic_flash_off)
                }

        })
        btnFoto.setOnClickListener(View.OnClickListener {
            if (!travarObturador) {
                travarObturador = true
                mCamera.takePicture(null, null, this@ScanActivity)

            }
        })
        imagePreview.setOnClickListener(View.OnClickListener {
            var intent = Intent(this.applicationContext, EdicaoActivity::class.java)
            startActivity(intent)
        })
    }

    override fun onResume() {
        super.onResume()

        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = flags

            window.decorView.setOnSystemUiVisibilityChangeListener(object : View.OnSystemUiVisibilityChangeListener {
                override fun onSystemUiVisibilityChange(visibility: Int) {
                    if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN === 0) {
                        window.decorView.systemUiVisibility = flags
                    }
                }
            })
        }

        verificarPermissoes()
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_3_0, this, mLoaderCallback)
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!")
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        }

        mPreferencias = PreferenceHelper.defaultPrefs(this)

        if (mBackgroundThread == null) {
            mBackgroundThread = HandlerThread("Thread de Processamento")
            mBackgroundThread!!.start()
        }

        if (mProcessamento == null) {
            mProcessamento = Processamento(mBackgroundThread!!.looper, this)
        }
        mProcessamento!!.processando = false
        limparDiretorio()
    }

    override fun surfaceCreated(p0: SurfaceHolder?) {
        try {
            val cameraId = melhorCamera()
            mCamera = Camera.open(cameraId)
        } catch (e: RuntimeException) {
            System.err.println(e)
            return
        }

        val cameraParametros = mCamera.parameters
        val pSize = melhorResolucaoFrame(mCamera)
        cameraParametros.setPreviewSize(pSize.width, pSize.height)

        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getRealSize(size)

        val displayWidth = Math.min(size.y, size.x)
        val displayHeight = Math.max(size.y, size.x)

        val displayRatio = displayHeight.toFloat() / displayWidth.toFloat()

        val previewRatio = pSize.width.toFloat() / pSize.height
        var previewHeight = displayHeight

        if (displayRatio > previewRatio) {
            var novosParametros = cameraSurfaceView.layoutParams
            previewHeight = (size.y.toFloat() / displayRatio * previewRatio).toInt()
            novosParametros.height = previewHeight
            cameraSurfaceView.layoutParams = novosParametros
        }
        val maxRes = maiorResolucaoFoto(previewRatio, mCamera)
        if (maxRes != null) {
            cameraParametros.setPictureSize(maxRes.width, maxRes.height)
            Log.d(TAG, "Maior resolução: " + maxRes.width + "x" + maxRes.height)
        }

        val pm = packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS)) {
            cameraParametros.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Log.d(TAG, "ativando o autofocus")
        } else {
            mAutoFocus = true
            Log.d(TAG, "autofocus não disponivel")
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            cameraParametros.flashMode = if (mCameraFlash) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
        }

        mCamera.parameters = cameraParametros


        if (mPreferencias!!["ROTACIONAR", false] as Boolean) {
            mCamera.setDisplayOrientation(270)
        } else {
            mCamera.setDisplayOrientation(90)
        }

        try {
            mCamera.setAutoFocusMoveCallback(Camera.AutoFocusMoveCallback { start, camera ->
                mAutoFocus = !start
                Log.d(TAG, "Auto foco change: $mAutoFocus")
            })
        } catch (e: Exception) {
            Log.d(TAG, "failed setting AutoFocusMoveCallback")
        }
        mAutoFocus = true

    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        atualizarCamera()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mCamera.stopPreview()
        mCamera.setPreviewCallback(null)
        mCamera.release()
    }

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (camera == null)
            return

        val tamanhoDaFoto = camera.parameters.previewSize
        if (!mProcessamento!!.processando && !travarObturador) {
            mProcessamento!!.processando = true

            val yuv = Mat(Size(tamanhoDaFoto.width.toDouble(), tamanhoDaFoto.height * 1.5), CvType.CV_8UC1)
            yuv.put(0, 0, data)
            val mat = Mat(Size(tamanhoDaFoto.width.toDouble(), tamanhoDaFoto.height.toDouble()), CvType.CV_8UC4)
            Imgproc.cvtColor(yuv, mat, Imgproc.COLOR_YUV2RGBA_NV21, 4)
            Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE)
            yuv.release()

            enviarRequisicao(TipoProcessamento.PREVIEW, mat)
        }
    }

    override fun onPictureTaken(data: ByteArray?, camera: Camera?) {
        if (camera == null)
            return


        val tamanhoDaFoto = camera.parameters.previewSize
        Log.d(TAG, "Foto tirada - ${tamanhoDaFoto.width} x ${tamanhoDaFoto.height}")

        //executa o som do obturador
        val meng = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = meng.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        if (volume != 0) {
            var sound = MediaPlayer.create(this, Uri.parse("file:///system/media/audio/ui/camera_click.ogg"))
            if (sound != null) {
                sound.start()
            }
        }
        doAsync {

            val fotoTmp = File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath}/", "photo.jpg")
            if (fotoTmp.exists()) {
                fotoTmp.delete()
            }
            try {
                val fos = FileOutputStream(fotoTmp.path)
                fos.write(data)
                fos.close()
                var img = Imgcodecs.imread(fotoTmp.absolutePath)
                fotoTmp.delete()
//            val mat = Mat(Size(tamanhoDaFoto.width.toDouble(), tamanhoDaFoto.height.toDouble()), CvType.CV_8UC1)
//            mat.put(0, 0, data)
//            val img = Imgcodecs.imdecode(mat, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED)
                Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                travarObturador = false
                mProcessamento!!.processando = true
                enviarRequisicao(TipoProcessamento.PICTURE, img)
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Erro ao gravar a imagem", e)
            } finally {
                this.runOnUiThread {
                    mCamera.startPreview()
                }
            }
        }
    }

    private fun enviarRequisicao(tipo: TipoProcessamento, obj: Any) {
        val msg = mProcessamento!!.obtainMessage()
        var _object = RequisicaoProcessamento(tipo, obj, 0, 0)
        msg.obj = _object
        mProcessamento!!.sendMessage(msg)
    }

    private fun verificarPermissoes() {

        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.CAMERA,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.INTERNET),
                    PERMISSIONS_REQUEST_WRITE)

        }

    }

    private fun verificarPermissoesCamera() {
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.CAMERA),
                    RESUME_PERMISSIONS_REQUEST_CAMERA)

        } else {
            habilitarCamera()
        }
    }

    private fun atualizarCamera() {
        try {
            mCamera.stopPreview()
        } catch (e: Exception) {
            Log.d(TAG, "${e.message}")
        }

        try {
            mCamera.setPreviewDisplay(mSurfaceHolder)
            mCamera.startPreview()
            mCamera.setPreviewCallback(this)
        } catch (e: Exception) {
            Log.d(TAG, "${e.message}")
        }

    }

    private fun habilitarCamera() {
        mSurfaceHolder = cameraSurfaceView!!.holder
        mSurfaceHolder!!.addCallback(this)
        mSurfaceHolder!!.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)

        cameraSurfaceView!!.visibility = (SurfaceView.VISIBLE)
    }

    private fun limparDiretorio() {
        val file = File(Processamento.caminhoPadrao)
        if (file.isDirectory) {
            var children = file.list()
            for (i in 0 until children.size) {
                File(file, children[i]).delete()
            }
        }
    }

    private fun ativarFlash(flashAtivo: Boolean): Boolean {
        val pm = packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            val parametros = mCamera.parameters
            parametros.flashMode = if (flashAtivo) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            mCamera.parameters = parametros
            Log.d(TAG, "flash: $flashAtivo")
            return flashAtivo
        }
        return false
    }


    private fun melhorCamera(): Int {
        var cameraId = -1

        val nCamera = Camera.getNumberOfCameras()
        //for every camera check
        for (i in 0 until nCamera) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                cameraId = i
                break
            }
            cameraId = i
        }
        return cameraId
    }

    private fun melhorResolucaoFrame(camera: Camera): Camera.Size {
        camera.lock()
        var maxWidth = 0
        var lisTamanhos = camera.parameters.supportedPreviewSizes.toMutableList()
        lisTamanhos.removeAt(0)
        return Collections.max(lisTamanhos, CompararAreaSizes())
    }

    private fun maiorResolucaoFoto(pvRatio: Float, camera: Camera): Camera.Size {
        var maxPixels = 0
        var ratioMaxPixels = 0
        var currentMaxRes: Camera.Size? = null
        var ratioCurrentMaxRes: Camera.Size? = null
        for (r in camera.parameters.supportedPictureSizes) {
            val pictureRatio = r.width.toFloat() / r.height
            Log.d(TAG, "Resolucao suportada: " + r.width + "x" + r.height + " ratio: " + pictureRatio)
            val resolutionPixels = r.width * r.height

            if (resolutionPixels > ratioMaxPixels && pictureRatio == pvRatio) {
                ratioMaxPixels = resolutionPixels
                ratioCurrentMaxRes = r
            }

            if (resolutionPixels > maxPixels) {
                maxPixels = resolutionPixels
                currentMaxRes = r
            }
        }

        val mAspect = mPreferencias!!["mAspect", true] as Boolean
        if (ratioCurrentMaxRes != null && mAspect) {

            Log.d(TAG, "Max supported picture resolution with preview aspect ratio: "
                    + ratioCurrentMaxRes.width + "x" + ratioCurrentMaxRes.height)
            return ratioCurrentMaxRes

        }

        return currentMaxRes as Camera.Size
    }

    //Carrega a openCV assyncrono
    private val mLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {
                    Log.i(TAG, "OpenCV carregada")
                    verificarPermissoesCamera()
                }
                else -> {
                    super.onManagerConnected(status)
                }
            }
        }
    }

    class doAsync(val handler: () -> Unit) : AsyncTask<Void, Void, Void>() {
        init {
            execute()
        }

        override fun doInBackground(vararg params: Void?): Void? {
            handler()
            return null
        }
    }

    companion object {

        private val CREATE_PERMISSIONS_REQUEST_CAMERA = 1
        private val RESUME_PERMISSIONS_REQUEST_CAMERA = 11
        private val PERMISSIONS_REQUEST_WRITE = 3
        private val TAG = "ScanActivity"

    }
}
