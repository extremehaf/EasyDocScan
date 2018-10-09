package scan.lucas.com.easydocscan.Adapters

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import io.realm.*
import scan.lucas.com.easydocscan.DAL.DocumentoRealm
import scan.lucas.com.easydocscan.Enum.TipoListagem
import scan.lucas.com.easydocscan.Enum.OrderBy
import scan.lucas.com.easydocscan.Interfaces.IPopupMenuListener
import scan.lucas.com.easydocscan.R
import kotlinx.android.synthetic.main.item_recycler_pdf_row.view.*
import scan.lucas.com.easydocscan.Utils.ToBitmap
import scan.lucas.com.easydocscan.Utils.ToDataMedia
import java.util.*


class PdfRealmRVAdapter(var tipoListagem: TipoListagem, var realm: Realm, data: OrderedRealmCollection<DocumentoRealm>, var onPopupMenuClicked: IPopupMenuListener): RealmRecyclerViewAdapter<DocumentoRealm, PdfRealmRVAdapter.DocumentosViewHolder>(data, true), Filterable {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentosViewHolder {
        //verigica se é grid ou lista para atribuir o layout especifico
        if (tipoListagem == TipoListagem.LIST) {
            val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_recycler_pdf_row, parent, false)
            return DocumentosViewHolder(view, onPopupMenuClicked)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recycler_pdf_grid, parent, false)
            return DocumentosViewHolder(view, onPopupMenuClicked)
        }
    }
    override fun onBindViewHolder(holder: DocumentosViewHolder, position: Int) {
        var objAtual = getItem(position)

        holder.data = objAtual
        holder.txtTitulo!!.text = objAtual?.name
        holder.txtDataCriacao!!.text = objAtual?.data_modificacao?.ToDataMedia()

        val nbytes = objAtual?.tamanho

        if(nbytes != null) {
            var format = android.text.format.Formatter.formatShortFileSize(holder.itemView.context, nbytes)
            holder.txtTamanho!!.text = format
        }

        if(tipoListagem == TipoListagem.GRID)
            holder.txtPaginas!!.text = "Páginas: ${objAtual?.paginas}"
        else
            holder.txtPaginas!!.text = objAtual?.paginas.toString()

        if (objAtual?.thumbnail != null)
            holder.preview!!.setImageBitmap(objAtual.thumbnail?.ToBitmap())
        else
            holder.preview!!.setImageResource(R.drawable.ic_pdf)
    }
    override fun getItemId(index: Int): Long {

        return getItem(index)!!.docid.toLong()
    }
    override fun getFilter(): Filter {
        val filter = DocumentosFilter(this)
        return filter
    }

    fun resultadoFiltro(nome: String) {

        val query = this.realm.where(DocumentoRealm::class.java)
        if (!nome.isNullOrEmpty()) {
            query.contains("name", nome, Case.INSENSITIVE)
        }
        updateData(query.findAllAsync())
    }
    fun OrderByData(order: OrderBy? = null) {

        val query = this.realm.where(DocumentoRealm::class.java)
        if (order != null) {
            if (order == OrderBy.DataDesc)
                query.sort("data_modificacao", Sort.DESCENDING)
            else
                query.sort("data_modificacao", Sort.ASCENDING)
        } else {
            query.sort("data_modificacao")
        }

        updateData(query.findAllAsync())
    }

    fun OrderByNome(order: OrderBy? = null) {

        val query = this.realm.where(DocumentoRealm::class.java)
        if (order != null) {
            if (order == OrderBy.NomeDesc)
                query.sort("name", Sort.DESCENDING)
            else
                query.sort("name", Sort.ASCENDING)
        } else {
            query.sort("name")
        }
        updateData(query.findAllAsync())
    }

    inner class DocumentosFilter(var adapter: PdfRealmRVAdapter) : Filter() {

        override fun performFiltering(constraint: CharSequence?): FilterResults {
            return FilterResults()
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            adapter.resultadoFiltro(constraint.toString())
        }

    }


    inner class DocumentosViewHolder(view: View, var popupMenuListener: IPopupMenuListener? = null) : RecyclerView.ViewHolder(view), PopupMenu.OnMenuItemClickListener {

        var txtTitulo: TextView? = null
        var txtDataCriacao: TextView? = null
        var txtPaginas: TextView? = null
        var txtTamanho: TextView? = null
        var preview: ImageView? = null
        var data: DocumentoRealm? = null
        var contextMenu: ImageView? = null

        init {

            preview = view.preview_foto
            txtTitulo = view.txt_titulo
            txtDataCriacao = view.txt_data
            txtPaginas = view.txt_paginas
            txtTamanho = view.txt_tamanho
            contextMenu = view.ic_context
            if (contextMenu != null) {
                contextMenu!!.setOnClickListener {
                    showPopupMenu(it)
                }
            }

        }

        override fun onMenuItemClick(item: MenuItem?): Boolean {
            when (item!!.itemId) {
                R.id.menu_baixar -> {
                    // Call popup menu listener passing the menu item that was clicked as well as the recycler view item position:
                    popupMenuListener!!.onPopupMenuClicked(item, adapterPosition)
                    return true
                }
                R.id.menu_visualizar -> {
                    popupMenuListener!!.onPopupMenuClicked(item, adapterPosition)
                    return true
                }
                else -> return false
            }
        }

        private fun showPopupMenu(view: View) {
            val popup = PopupMenu(view.context, view)
            val inflater = popup.menuInflater
            inflater.inflate(R.menu.menu_pdf, popup.menu)
            popup.setOnMenuItemClickListener(this)
            popup.show()
        }

    }
}