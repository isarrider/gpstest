/*
 * Copyright (C) 2026 The GPSTest contributors
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
package com.android.gpstest.ui.track

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.gpstest.Application.Companion.app
import com.android.gpstest.Application.Companion.prefs
import com.android.gpstest.R
import java.util.Collections

/**
 * "Arrange cards" dialog of the Track screen, opened from the toolbar's filter button there. Each
 * card has a checkbox (shown or not) and a drag handle that sets its position, like the
 * "Show only these GNSS" dialog on the other screens.
 */
class TrackCardsDialog : DialogFragment() {

    private lateinit var settings: MutableList<TrackSectionSetting>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val saved = savedInstanceState?.getString(STATE_SETTINGS)
            ?: prefs.getString(app.getString(R.string.pref_key_track_cards), null)
        settings = parseTrackSections(saved).toMutableList()

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.track_cards_dialog, null)
        val list = view.findViewById<RecyclerView>(R.id.gnss_list)
        val adapter = CardAdapter()
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
                // After the drop, update the move up/down actions, which depend on the position
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
            .setTitle(R.string.track_cards_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.save) { dialog, _ ->
                prefs.edit()
                    .putString(app.getString(R.string.pref_key_track_cards), formatTrackSections(settings))
                    .apply()
                dialog.dismiss()
            }
            .setNeutralButton(R.string.track_cards_dialog_reset) { dialog, _ ->
                prefs.edit().remove(app.getString(R.string.pref_key_track_cards)).apply()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SETTINGS, formatTrackSections(settings))
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.Holder>() {
        var touchHelper: ItemTouchHelper? = null

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: CheckBox = view.findViewById(R.id.gnss_check)
            val handle: ImageView = view.findViewById(R.id.gnss_drag_handle)
            var moveUpAction = View.NO_ID
            var moveDownAction = View.NO_ID
        }

        override fun getItemCount() = settings.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.gnss_filter_item, parent, false)
            )
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                settings[position] = settings[position].copy(shown = isChecked)
            }
            // Screen readers can't drag, they move items with the "Move up"/"Move down" actions
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(holder)
                false
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val setting = settings[position]
            holder.check.text = getString(labelOf(setting.section))
            holder.check.isChecked = setting.shown

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
            if (position < settings.size - 1) {
                holder.moveDownAction = ViewCompat.addAccessibilityAction(
                    holder.itemView, getString(R.string.filter_dialog_move_down)
                ) { _, _ ->
                    val from = holder.bindingAdapterPosition
                    if (from in 0 until settings.size - 1) {
                        move(from, from + 1)
                        refreshPositions()
                    }
                    true
                }
            }
        }

        fun move(from: Int, to: Int) {
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return
            Collections.swap(settings, from, to)
            notifyItemMoved(from, to)
        }

        fun refreshPositions() {
            notifyItemRangeChanged(0, settings.size)
        }
    }

    companion object {
        private const val STATE_SETTINGS = ".settings"

        @StringRes
        fun labelOf(section: TrackSection): Int = when (section) {
            TrackSection.SUMMARY -> R.string.track_card_summary
            TrackSection.SPEED -> R.string.track_section_speed
            TrackSection.ELEVATION -> R.string.track_section_elevation
            TrackSection.GNSS -> R.string.track_section_gnss
            TrackSection.BATTERY -> R.string.track_section_battery
            TrackSection.FILE -> R.string.track_section_file
            TrackSection.CHART_DISTANCE -> R.string.track_card_chart_distance
            TrackSection.SPLITS -> R.string.track_card_splits
            TrackSection.CHART_ELEVATION -> R.string.track_card_chart_elevation
            TrackSection.CHART_SPEED -> R.string.track_card_chart_speed
            TrackSection.CHART_SATELLITES -> R.string.track_card_chart_satellites
            TrackSection.CHART_BATTERY -> R.string.track_card_chart_battery
        }
    }
}
