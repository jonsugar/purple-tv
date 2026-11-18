package com.purpletv.home

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.widget.ListView
import android.widget.TextView
import com.purpletv.R
import com.purpletv.twitch.Category
import com.purpletv.twitch.FollowKind.CATEGORY
import com.purpletv.twitch.FollowKind.CHANNEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test_pins", Context.MODE_PRIVATE)
    private val pins = PinStore(prefs)
    private val first = FavouritePin("1", "First")
    private val second = FavouritePin("2", "Second")

    @Test fun appendsAndReordersWithoutAffectingOtherAccountsOrItemTypes() {
        pins.add("account-a", CHANNEL, first)
        pins.add("account-a", CHANNEL, second)
        pins.add("account-a", CHANNEL, first)
        pins.add("account-b", CHANNEL, first)
        pins.add("account-a", CATEGORY, first)
        assertEquals(listOf(first, second), pins.list("account-a", CHANNEL))

        pins.move("account-a", CHANNEL, first.id, 1)
        val reopened = PinStore(prefs)
        assertEquals(listOf(second, first), reopened.list("account-a", CHANNEL))
        assertEquals(listOf(first), reopened.list("account-b", CHANNEL))
        assertEquals(listOf(first), reopened.list("account-a", CATEGORY))
        assertEquals(emptyList<FavouritePin>(), reopened.list(null, CHANNEL))
    }

    @Test fun offlineCategoriesCanBeReorderedAndReturnInTheirSavedPositions() {
        pins.add("account-a", CATEGORY, first)
        pins.add("account-a", CATEGORY, second)
        pins.move("account-a", CATEGORY, second.id, -1)
        val saved = pins.list("account-a", CATEGORY)
        val firstCategory = Category(first.id, first.name, "")
        val secondCategory = Category(second.id, second.name, "")
        val regular = Category("3", "Regular", "")

        assertEquals(emptyList<Category>() to listOf(regular), splitByPins(listOf(regular), saved, Category::id))
        assertEquals(listOf(firstCategory) to listOf(regular), splitByPins(listOf(firstCategory, regular), saved, Category::id))
        assertEquals(listOf(secondCategory, firstCategory) to listOf(regular),
            splitByPins(listOf(regular, firstCategory, secondCategory), saved, Category::id))
        pins.remove("account-a", CATEGORY, first.id)
        assertEquals(listOf(secondCategory) to listOf(regular, firstCategory),
            splitByPins(listOf(regular, firstCategory, secondCategory), pins.list("account-a", CATEGORY), Category::id))
    }

    @Test fun reorderDialogListsAndMovesSavedCategoriesWithoutLiveData() {
        pins.add("account-a", CATEGORY, first)
        pins.add("account-a", CATEGORY, second)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = TextView(activity)
        activity.setContentView(anchor)
        showReorderPins(anchor, pins, "account-a", CATEGORY)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val list = dialog.findViewById<ListView>(R.id.reorder_list)

        assertEquals(listOf(first.name, second.name), (0 until list.adapter.count).map { list.adapter.getItem(it) })
        assertTrue(list.performItemClick(null, 1, list.adapter.getItemId(1)))
        assertTrue(dialog.findViewById<TextView>(R.id.reorder_up).performClick())
        assertEquals(listOf(second, first), pins.list("account-a", CATEGORY))
        assertEquals(listOf(second.name, first.name), (0 until list.adapter.count).map { list.adapter.getItem(it) })
        dialog.dismiss()
    }
}
