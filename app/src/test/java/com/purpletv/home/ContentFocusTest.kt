package com.purpletv.home

import android.app.Activity
import android.os.Looper
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.purpletv.R
import com.purpletv.ui.CardRow
import com.purpletv.ui.ContentGrid
import com.purpletv.ui.RowKind
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentFocusTest {
    @Test fun downFromLastCardStaysInContentAndLeftStillEntersTheRail() = withActivity(R.layout.activity_main) { activity ->
        val page = activity.showPage(R.layout.page_browse)
        val tab = page.findViewById<View>(R.id.tab_categories)
        val results = ContentGrid(page.findViewById(R.id.browse_results), R.string.search_no_results, { tab }) {}
        results.populate(FocusCards(4))
        activity.layoutContent()

        val first = results.grid.cardAt(0)
        val lastRow = results.grid.cardAt(2)
        assertTrue(first.requestFocusFromTouch())
        assertSame(lastRow, first.focusSearch(View.FOCUS_DOWN))
        assertTrue(lastRow.requestFocus())
        val railItem = activity.findViewById<View>(R.id.nav_profile)
        val window = activity.findViewById<ViewGroup>(android.R.id.content)
        assertSame(railItem, FocusFinder.getInstance().findNextFocus(window, lastRow, View.FOCUS_DOWN))
        assertSame(lastRow, lastRow.focusSearch(View.FOCUS_DOWN))
        assertSame(railItem, lastRow.focusSearch(View.FOCUS_LEFT))
    }

    @Test fun upFromFollowingGridReachesTheSelectedTabWithOrWithoutPins() = withActivity(R.layout.activity_main) { activity ->
        val page = activity.showPage(R.layout.page_following)
        val tab = page.findViewById<View>(R.id.following_channels)
        val results = ContentGrid(activity, R.string.following_channels_empty, { tab }) {}
        page.findViewById<ViewGroup>(R.id.following_body).addView(results.view)
        results.populate(FocusCards(2))
        val sections = FollowingSections(activity, results.grid, R.string.following_followed_channels,
            RowKind.CHANNELS, FocusCards(1))
        activity.layoutContent()

        val regular = results.grid.cardAt(1)
        assertTrue(regular.requestFocusFromTouch())
        assertSame(tab, regular.focusSearch(View.FOCUS_UP))

        sections.show(pinned = 1, regular = 2)
        activity.layoutContent()
        val row = results.grid.cardAt(0).findViewById<CardRow>(R.id.row_list)
        val pin = row.cardAt(0)
        val firstRegular = results.grid.cardAt(1)
        assertTrue(firstRegular.requestFocus())
        assertSame(pin, firstRegular.focusSearch(View.FOCUS_UP))
        assertTrue(pin.requestFocus())
        assertSame(tab, pin.focusSearch(View.FOCUS_UP))
    }

    @Test fun upFromCategoryGridReachesTheLanguageControl() = withActivity(R.layout.activity_category) { activity ->
        val language = activity.findViewById<View>(R.id.category_language)
        val results = ContentGrid(activity.findViewById(R.id.category_results), R.string.category_empty, { language }) {}
        results.populate(FocusCards(2))
        activity.layoutContent()

        val card = results.grid.cardAt(1)
        assertTrue(card.requestFocusFromTouch())
        assertSame(language, card.focusSearch(View.FOCUS_UP))
        assertSame(card, card.focusSearch(View.FOCUS_DOWN))
    }

    @Test fun scrollingBackToTheFirstBrowseRowLetsUpLeaveTheGrid() = withActivity(R.layout.activity_main) { activity ->
        val page = activity.showPage(R.layout.page_browse)
        val tab = page.findViewById<View>(R.id.tab_categories)
        val results = ContentGrid(page.findViewById(R.id.browse_results), R.string.search_no_results, { tab }) {}
        results.populate(FocusCards(20))
        activity.layoutContent()

        val first = results.grid.cardAt(1)
        assertTrue(first.requestFocusFromTouch())
        var focused = first
        repeat(6) {
            val next = checkNotNull(focused.focusSearch(View.FOCUS_DOWN))
            assertTrue(next !== focused && next.requestFocus())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            focused = next
        }
        assertTrue(results.grid.canScrollVertically(-1))
        repeat(6) {
            val next = checkNotNull(focused.focusSearch(View.FOCUS_UP))
            assertTrue(next !== focused && next.requestFocus())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            focused = next
        }
        assertSame(tab, focused.focusSearch(View.FOCUS_UP))
    }

    private fun withActivity(layout: Int, check: (Activity) -> Unit) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.setTheme(R.style.Theme_PurpleTV)
            activity.setContentView(layout)
            check(activity)
        }
    }

    private fun Activity.showPage(layout: Int): View {
        val container = findViewById<ViewGroup>(R.id.content)
        return layoutInflater.inflate(layout, container, false).also(container::addView)
    }

    private fun Activity.layoutContent() {
        val windowContent = findViewById<View>(android.R.id.content)
        windowContent.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        windowContent.layout(0, 0, 960, 540)
    }

    private fun ContentGrid.populate(cards: FocusCards) {
        grid.layoutManager = GridLayoutManager(view.context, 2)
        grid.adapter = cards
        show(cards.itemCount, failed = false)
    }

    private fun RecyclerView.cardAt(position: Int): View = checkNotNull(findViewHolderForAdapterPosition(position)).itemView

    private class FocusCards(private val count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = View(parent.context).apply {
                isFocusable = true
                nextFocusLeftId = R.id.nav_profile
                layoutParams = RecyclerView.LayoutParams(150, 90)
            }
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }
}
