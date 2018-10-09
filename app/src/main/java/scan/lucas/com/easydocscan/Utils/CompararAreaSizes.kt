package scan.lucas.com.easydocscan.Utils

import android.hardware.Camera
import java.lang.Long.signum
import java.util.*


internal class CompararAreaSizes : Comparator<Camera.Size> {


    override fun compare(lhs: Camera.Size, rhs: Camera.Size) =
            signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)

}
