package scan.lucas.com.easydocscan.Interfaces

import android.view.MenuItem

interface IPopupMenuListener {
    fun onPopupMenuClicked(menuItem: MenuItem, adapterPosition: Int)
}