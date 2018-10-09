package scan.lucas.com.easydocscan.Adapters

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.support.v4.view.MotionEventCompat
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import scan.lucas.com.easydocscan.Interfaces.IItemTouchHelperAdapter
import scan.lucas.com.easydocscan.Interfaces.IOnStartDragListener
import scan.lucas.com.easydocscan.R
import scan.lucas.com.easydocscan.Utils.Conversor
import java.io.File
import java.util.*
import java.util.Collections.swap



class PreviewRVAdapter(private val list: ArrayList<String>, var mDragStartListener: IOnStartDragListener
) : RecyclerView.Adapter<PreviewRVAdapter.Fotos>(), IItemTouchHelperAdapter {

    var mContext: Context? = null
    inner class Fotos(view: View) : RecyclerView.ViewHolder(view) {

        var imageView: ImageView? = null

        init {

            imageView = view.findViewById(R.id.imgAparelho) as ImageView

        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Fotos {

        mContext = parent.context
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.preview_list, parent, false)

        return Fotos(itemView)
    }

    override fun onBindViewHolder(holder: Fotos, position: Int) {

        var bmp = BitmapFactory.decodeFile(File(list.get(position)).absolutePath)
        val ratio = bmp.width.toDouble() / bmp.height.toDouble()

        if (bmp.width > bmp.height) {
            val new_width = Conversor.DpsToPixel(300, mContext as Context)

            var newHeight = Math.round(new_width.toDouble() / ratio).toInt()

            var imageBitmap = ThumbnailUtils.extractThumbnail(bmp
                    , new_width, newHeight)

            holder.imageView!!.setImageBitmap(imageBitmap)
        } else {
            val newHeight = Conversor.DpsToPixel(300, mContext as Context)
            var new_width = Math.round(newHeight.toDouble() * ratio).toInt()


            var imageBitmap = ThumbnailUtils.extractThumbnail(bmp, new_width, newHeight)

            holder.imageView!!.setImageBitmap(imageBitmap)
        }

        holder.imageView!!.setOnTouchListener(View.OnTouchListener { view, motionEvent ->
            if (MotionEventCompat.getActionMasked(motionEvent) ==
                    MotionEvent.ACTION_MOVE) {
                mDragStartListener?.onStartDrag(holder);
            }
             false;
        })
    }

    override fun onItemDismiss(position: Int) {
        list.removeAt(position)
        notifyItemRemoved(position)
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        Collections.swap(list, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun getItemCount(): Int {
        return list.size
    }

}

