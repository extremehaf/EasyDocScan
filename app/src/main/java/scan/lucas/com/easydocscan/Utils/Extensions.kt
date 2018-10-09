package scan.lucas.com.easydocscan.Utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

fun Date.ToDataCompleta():String{
    return DateFormat.getDateInstance(DateFormat.LONG).format(this)
}
fun Date.ToDataMedia():String{
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(this)
}

fun ByteArray.ToBitmap(): Bitmap {
    return BitmapFactory.decodeByteArray(this, 0, this.size)
}
fun Bitmap.ToByteArray(): ByteArray {
    val byteArrayOutputStream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
    return byteArrayOutputStream.toByteArray()
}