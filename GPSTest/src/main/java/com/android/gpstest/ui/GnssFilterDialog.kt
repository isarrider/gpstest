/*
 * Copyright (C) 2026 Sean J. Barbeau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.gpstest.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.gpstest.Application.Companion.app
import com.android.gpstest.Application.Companion.prefs
import com.android.gpstest.R
import com.android.gpstest.library.model.GnssType
import com.android.gpstest.library.util.LibUIUtils
import com.android.gpstest.library.util.PreferenceUtils
import com.android.gpstest.ui.status.formatGnssOrder
import com.android.gpstest.ui.status.parseGnssOrder
import java.util.Collections

/**
 * "Show only these GNSS" dialog. Each constellation has a checkbox (shown or not, on all screens)
 * and a drag handle that sets its position in the Status screen's grouped satellite list.
 */
class GnssFilterDialog : DialogFragment() {

    private lateinit var order: MutableList<GnssType>
    private lateinit var checked: MutableSet<GnssType>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (savedInstanceState != null) {
            order = parseGnssOrder(savedInstanceState.getString(STATE_ORDER)).toMutableList()
            checked = savedInstanceState.getStringArray(STATE_CHECKED).orEmpty()
                .mapNotNull { name -> GnssType.values().firstOrNull { it.name == name } }
                .toMutableSet()
        } else {
            order = parseGnssOrder(
                prefs.getString(app.getString(R.string.pref_key_gnss_order), null)
            ).toMutableList()
            checked = PreferenceUtils.gnssFilter(app, prefs).toMutableSet()
        }

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.gnss_filter_dialog, null)
        val list = view.findViewById<RecyclerView>(R.id.gnss_list)
        val adapter = GnssAdapter()
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // After the drop, update the move up/down actions, which depend on the position.
                // Not during the drag, as rebinding the dragged row would cancel it.
                adapter.refreshPositions()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Swiping isn't used
            }

            // Only the handle starts a drag, so tapping and scrolling work as usual
            override fun isLongPressDragEnabled() = false
        })
        adapter.touchHelper = touchHelper
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        touchHelper.attachToRecyclerView(list)

        return AlertDialog.Builder(requireActivity())
            .setTitle(R.string.filter_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.save) { dialog, _ ->
                save()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ORDER, formatGnssOrder(order))
        outState.putStringArray(STATE_CHECKED, checked.map { it.name }.toTypedArray())
    }

    private fun save() {
        // Like before, nothing checked means all constellations are shown
        PreferenceUtils.saveGnssFilter(app, order.filter { it in checked }.toCollection(LinkedHashSet()), prefs)
        prefs.edit().putString(app.getString(R.string.pref_key_gnss_order), formatGnssOrder(order)).apply()
    }

    private inner class GnssAdapter : RecyclerView.Adapter<GnssAdapter.Holder>() {
        var touchHelper: ItemTouchHelper? = null

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: CheckBox = view.findViewById(R.id.gnss_check)
            val handle: ImageView = view.findViewById(R.id.gnss_drag_handle)
            var moveUpAction = View.NO_ID
            var moveDownAction = View.NO_ID
        }

        override fun getItemCount() = order.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.gnss_filter_item, parent, false)
            )
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                if (isChecked) checked.add(order[position]) else checked.remove(order[position])
            }
            // Screen readers can't drag, they move items with the "Move up"/"Move down" actions
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(holder)
                false
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val gnssType = order[position]
            holder.check.text = LibUIUtils.getGnssDisplayName(app, gnssType)
            // The listener sees the same value here, so this doesn't change the selection
            holder.check.isChecked = gnssType in checked

            ViewCompat.removeAccessibilityAction(holder.itemView, holder.moveUpAction)
            ViewCompat.removeAccessibilityAction(holder.itemView, holder.moveDownAction)
            holder.moveUpAction = View.NO_ID
            holder.moveDownAction = View.NO_ID
            if (position > 0) {
                holder.moveUpAction = ViewCompat.addAccessibilityAction(
                    holder.itemView, getString(R.string.filter_dialog_move_up)
                ) { _, _ ->
                    val from = holder.bindingAdapterPosition
                    if (from > 0) {
                        move(from, from - 1)
                        refreshPositions()
                    }
                    true
                }
            }
            if (position < order.size - 1) {
                holder.moveDownAction = ViewCompat.addAccessibilityAction(
                    holder.itemView, getString(R.string.filter_dialog_move_down)
                ) { _, _ ->
                    val from = holder.bindingAdapterPosition
                    if (from in 0 until order.size - 1) {
                        move(from, from + 1)
                        refreshPositions()
                    }
                    true
                }
            }
        }

        fun move(from: Int, to: Int) {
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return
            Collections.swap(order, from, to)
            notifyItemMoved(from, to)
        }

        fun refreshPositions() {
            notifyItemRangeChanged(0, order.size)
        }
    }

    companion object {
        private const val STATE_ORDER = ".order"
        private const val STATE_CHECKED = ".checked"
    }
}
