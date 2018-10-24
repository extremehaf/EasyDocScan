package scan.lucas.com.easydocscan

import android.content.Context
import android.util.Log
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobile.client.AWSMobileClient
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ListObjectsV2Request
import com.amazonaws.services.s3.model.S3ObjectSummary
import scan.lucas.com.easydocscan.Enum.OrderBy
import java.io.File
import com.amazonaws.services.s3.model.DeleteObjectRequest
import com.amazonaws.services.s3.model.ObjectMetadata


class AmazonS3Conection(bucketName: String, key: String, secrect: String) {

    private var PREFIX = "LMELO"
    private var BUCKET = "osas"
    private var KEY = ""
    private var SECRET = ""

    var s3Client: AmazonS3Client? = null
    private var credenciais: BasicAWSCredentials? = null
    private var sTransferUtility: TransferUtility? = null

    init {
        this.BUCKET = bucketName
        this.KEY = key
        this.SECRET = secrect
        credenciais = BasicAWSCredentials(KEY, SECRET)
        s3Client = AmazonS3Client(credenciais)
        s3Client!!.setRegion(Region.getRegion(Regions.SA_EAST_1))
    }

    fun deletarArquivo(keyName: String): Boolean {
        try {
            if (s3Client != null) {
                s3Client!!.deleteObject(DeleteObjectRequest(this.BUCKET, keyName))
                return true;
            }
            return false
        } catch (e: Exception) {
            Log.e("S3", "${e.message}")
            return false
        }
    }

    fun listarArquivos(order: OrderBy, iniciarAposItem: String = ""): List<S3ObjectSummary> {
        val listReq = ListObjectsV2Request().withBucketName(BUCKET)
                .withPrefix(PREFIX)

        if (!iniciarAposItem.isNullOrEmpty())
            listReq.withStartAfter(iniciarAposItem)


        var result = s3Client!!.listObjectsV2(listReq)

        when (order) {
            OrderBy.NomeAsc -> {
                result.objectSummaries.sortBy { s3ObjectSummary: S3ObjectSummary? -> s3ObjectSummary?.key }
            }
            OrderBy.NomeDesc -> {
                result.objectSummaries.sortByDescending { s3ObjectSummary: S3ObjectSummary? -> s3ObjectSummary?.key }
            }
            OrderBy.DataAsc -> {
                result.objectSummaries.sortBy { s3ObjectSummary: S3ObjectSummary? -> s3ObjectSummary?.lastModified }
            }
            OrderBy.DataDesc -> {
                result.objectSummaries.sortByDescending { s3ObjectSummary: S3ObjectSummary? -> s3ObjectSummary?.lastModified }
            }
        }

        return result.objectSummaries.filter { obj ->
            !obj.key.endsWith("/")
        }
    }

    fun getTransferUtility(context: Context): TransferUtility? {
        if (sTransferUtility == null) {
            sTransferUtility = TransferUtility.builder()
                    .defaultBucket(BUCKET)
                    .context(context)
                    .awsConfiguration(AWSMobileClient.getInstance().configuration)
                    .s3Client(this.s3Client).build()
        }

        return sTransferUtility
    }

    fun downloadArquivo(key: String, context: Context, tmpFile: File, transferListener: TransferListener) {
        val transferUtility = TransferUtility.builder()
                .context(context)
                .awsConfiguration(AWSMobileClient.getInstance().configuration)
                .defaultBucket(BUCKET)
                .s3Client(s3Client)
                .build()


        val downloadObserver = transferUtility.download(key, tmpFile)

        downloadObserver.setTransferListener(transferListener)
    }

    fun uploadArquivo(key: String, context: Context, tmpFile: File, objectMetadata:ObjectMetadata, transferListener: TransferListener) {
        val transferUtility = TransferUtility.builder()
                .context(context)
                .awsConfiguration(AWSMobileClient.getInstance().configuration)
                .defaultBucket(BUCKET)
                .s3Client(s3Client)
                .build()


        val uploadObserver = transferUtility.upload(BUCKET, key, tmpFile, objectMetadata)
        uploadObserver.setTransferListener(transferListener)
    }
}
