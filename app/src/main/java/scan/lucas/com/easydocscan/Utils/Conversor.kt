package scan.lucas.com.easydocscan.Utils

import android.content.Context

class Conversor {
    companion object {
        fun DpsToPixel(dps: Int, mContext: Context): Int {
            val scale = mContext.resources.displayMetrics.density
            return (dps * scale + 0.5f).toInt()
        }
    }
}