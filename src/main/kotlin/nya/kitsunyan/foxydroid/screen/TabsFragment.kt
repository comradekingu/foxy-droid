package nya.kitsunyan.foxydroid.screen

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.ProductItem
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.SyncService
import nya.kitsunyan.foxydroid.utility.RxUtils
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.*
import nya.kitsunyan.foxydroid.utility.extension.resources.*
import nya.kitsunyan.foxydroid.utility.extension.text.*
import nya.kitsunyan.foxydroid.widget.EnumRecyclerAdapter
import kotlin.math.*

class TabsFragment: Fragment() {
  companion object {
    private const val STATE_SEARCH_QUERY = "searchQuery"
    private const val STATE_SHOW_CATEGORIES = "showCategories"
    private const val STATE_CATEGORIES = "categories"
    private const val STATE_CATEGORY = "category"
    private const val STATE_ORDER = "order"
  }

  private class Layout(view: View) {
    val tabs = view.findViewById<LinearLayout>(R.id.tabs)!!
    val categoryLayout = view.findViewById<ViewGroup>(R.id.category_layout)!!
    val categoryChange = view.findViewById<View>(R.id.category_change)!!
    val categoryName = view.findViewById<TextView>(R.id.category_name)!!
    val categoryIcon = view.findViewById<ImageView>(R.id.category_icon)!!
  }

  private var sortOrderMenu: Pair<MenuItem, List<MenuItem>>? = null
  private var syncRepositoriesMenuItem: MenuItem? = null
  private var layout: Layout? = null
  private var categoriesList: RecyclerView? = null
  private var viewPager: ViewPager2? = null

  private var showCategories = false
    set(value) {
      if (field != value) {
        field = value
        val layout = layout
        layout?.tabs?.let { (0 until it.childCount)
          .forEach { index -> it.getChildAt(index)!!.isEnabled = !value } }
        layout?.categoryIcon?.scaleY = if (value) -1f else 1f
        if ((categoriesList?.parent as? View)?.height ?: 0 > 0) {
          animateCategoriesList()
        }
      }
    }

  private var searchQuery = ""
  private var categories = emptyList<String>()
  private var category = ""
  private var order = ProductItem.Order.NAME

  private val syncConnection = Connection(SyncService::class.java, onBind = { _, _ ->
    viewPager?.let {
      val source = ProductsFragment.Source.values()[it.currentItem]
      updateUpdateNotificationBlocker(source)
    }
  })

  private var categoriesDisposable: Disposable? = null
  private var categoriesAnimator: ValueAnimator? = null

  private var needSelectUpdates = false

  private val productFragments: Sequence<ProductsFragment>
    get() = if (host == null) emptySequence() else
      childFragmentManager.fragments.asSequence().mapNotNull { it as? ProductsFragment }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return inflater.inflate(R.layout.fragment, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    syncConnection.bind(requireContext())

    val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
    screenActivity.onFragmentViewCreated(toolbar)
    toolbar.setTitle(R.string.app_name)

    val searchView = SearchView(toolbar.context)
    searchView.maxWidth = Int.MAX_VALUE
    searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
      override fun onQueryTextSubmit(query: String?): Boolean {
        searchView.clearFocus()
        return true
      }

      override fun onQueryTextChange(newText: String?): Boolean {
        if (isResumed) {
          searchQuery = newText.orEmpty()
          productFragments.forEach { it.setSearchQuery(newText.orEmpty()) }
        }
        return true
      }
    })

    toolbar.menu.apply {
      if (Android.sdk(28)) {
        setGroupDividerEnabled(true)
      }

      add(0, R.id.toolbar_search, 0, R.string.search)
        .setIcon(Utils.getToolbarIcon(toolbar.context, R.drawable.ic_search))
        .setActionView(searchView)
        .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)

      sortOrderMenu = addSubMenu(0, 0, 0, R.string.sort_order)
        .setIcon(Utils.getToolbarIcon(toolbar.context, R.drawable.ic_sort))
        .let { menu ->
          menu.item.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
          val items = ProductItem.Order.values().map { order ->
            menu
              .add(order.titleResId)
              .setOnMenuItemClickListener { item ->
                this@TabsFragment.order = order
                item.isChecked = true
                productFragments.forEach { it.setOrder(order) }
                true
              }
          }
          menu.setGroupCheckable(0, true, true)
          Pair(menu.item, items)
        }

      syncRepositoriesMenuItem = add(0, 0, 0, R.string.sync_repositories)
        .setIcon(Utils.getToolbarIcon(toolbar.context, R.drawable.ic_sync))
        .setOnMenuItemClickListener {
          syncConnection.binder?.sync(SyncService.SyncRequest.MANUAL)
          true
        }

      add(1, 0, 0, R.string.repositories)
        .setOnMenuItemClickListener {
          view.post { screenActivity.navigateRepositories() }
          true
        }

      add(1, 0, 0, R.string.preferences)
        .setOnMenuItemClickListener {
          view.post { screenActivity.navigatePreferences() }
          true
        }
    }

    searchQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
    productFragments.forEach { it.setSearchQuery(searchQuery) }

    val toolbarExtra = view.findViewById<FrameLayout>(R.id.toolbar_extra)
    toolbarExtra.addView(toolbarExtra.inflate(R.layout.tabs_toolbar))
    val layout = Layout(view)
    this.layout = layout

    layout.tabs.background = TabsBackgroundDrawable(layout.tabs.context,
      layout.tabs.layoutDirection == View.LAYOUT_DIRECTION_RTL)
    ProductsFragment.Source.values().forEach {
      val tab = TextView(layout.tabs.context)
      val selectedColor = tab.context.getColorFromAttr(android.R.attr.textColorPrimary).defaultColor
      val normalColor = tab.context.getColorFromAttr(android.R.attr.textColorSecondary).defaultColor
      tab.gravity = Gravity.CENTER
      tab.typeface = TypefaceExtra.medium
      tab.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(selectedColor, normalColor)))
      tab.setTextSizeScaled(14)
      tab.isAllCaps = true
      tab.text = getString(it.titleResId)
      tab.background = tab.context.getDrawableFromAttr(android.R.attr.selectableItemBackground)
      tab.setOnClickListener { _ ->
        setSelectedTab(it)
        viewPager!!.currentItem = it.ordinal
      }
      layout.tabs.addView(tab, 0, LinearLayout.LayoutParams.MATCH_PARENT)
      (tab.layoutParams as LinearLayout.LayoutParams).weight = 1f
    }

    showCategories = savedInstanceState?.getByte(STATE_SHOW_CATEGORIES)?.toInt() ?: 0 != 0
    categories = savedInstanceState?.getStringArrayList(STATE_CATEGORIES).orEmpty()
    category = savedInstanceState?.getString(STATE_CATEGORY).orEmpty()
    layout.categoryChange.setOnClickListener { showCategories = categories.isNotEmpty() && !showCategories }

    order = savedInstanceState?.getString(STATE_ORDER)?.let(ProductItem.Order::valueOf) ?: ProductItem.Order.NAME
    sortOrderMenu!!.second[order.ordinal].isChecked = true
    productFragments.forEach { it.setOrder(order) }

    val content = view.findViewById<FrameLayout>(R.id.fragment_content)

    viewPager = ViewPager2(content.context).apply {
      id = R.id.fragment_pager
      adapter = object: FragmentStateAdapter(this@TabsFragment) {
        override fun getItemCount(): Int = ProductsFragment.Source.values().size
        override fun createFragment(position: Int): Fragment = ProductsFragment(ProductsFragment
          .Source.values()[position])
      }
      content.addView(this, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
      registerOnPageChangeCallback(pageChangeCallback)
      offscreenPageLimit = 1
    }

    categoriesDisposable = Observable.just(Unit)
      .concatWith(Database.observable(Database.Subject.Products))
      .observeOn(Schedulers.io())
      .flatMapSingle { RxUtils.querySingle { Database.CategoryAdapter.getAll(it) } }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe {
        val categories = it.sorted()
        if (this.categories != categories) {
          this.categories = categories
          updateCategory()
        }
      }
    updateCategory()

    val categoriesList = RecyclerView(toolbar.context).apply {
      id = R.id.categories_list
      layoutManager = LinearLayoutManager(context)
      isMotionEventSplittingEnabled = false
      isVerticalScrollBarEnabled = false
      setHasFixedSize(true)
      this.adapter = CategoriesAdapter({ categories }) {
        if (showCategories) {
          showCategories = false
          category = it
          updateCategory()
        }
      }
      setBackgroundColor(context.getColorFromAttr(android.R.attr.colorPrimaryDark).defaultColor)
      elevation = resources.sizeScaled(4).toFloat()
      content.addView(this, FrameLayout.LayoutParams.MATCH_PARENT, 0)
      visibility = View.GONE
    }
    this.categoriesList = categoriesList

    var lastContentHeight = -1
    content.viewTreeObserver.addOnGlobalLayoutListener {
      if (this.view != null) {
        val initial = lastContentHeight <= 0
        val contentHeight = content.height
        if (lastContentHeight != contentHeight) {
          lastContentHeight = contentHeight
          if (initial) {
            categoriesList.layoutParams.height = if (showCategories) contentHeight else 0
            categoriesList.visibility = if (showCategories) View.VISIBLE else View.GONE
            categoriesList.requestLayout()
          } else {
            animateCategoriesList()
          }
        }
      }
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()

    sortOrderMenu = null
    syncRepositoriesMenuItem = null
    layout = null
    categoriesList = null
    viewPager = null

    syncConnection.unbind(requireContext())
    categoriesDisposable?.dispose()
    categoriesDisposable = null
    categoriesAnimator?.cancel()
    categoriesAnimator = null
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    outState.putString(STATE_SEARCH_QUERY, searchQuery)
    outState.putByte(STATE_SHOW_CATEGORIES, if (showCategories) 1 else 0)
    outState.putStringArrayList(STATE_CATEGORIES, ArrayList(categories))
    outState.putString(STATE_CATEGORY, category)
    outState.putString(STATE_ORDER, order.name)
  }

  override fun onViewStateRestored(savedInstanceState: Bundle?) {
    super.onViewStateRestored(savedInstanceState)

    if (needSelectUpdates) {
      needSelectUpdates = false
      selectUpdatesInternal(false)
    }
  }

  override fun onAttachFragment(childFragment: Fragment) {
    super.onAttachFragment(childFragment)

    if (view != null && childFragment is ProductsFragment) {
      childFragment.setSearchQuery(searchQuery)
      childFragment.setCategory(category)
      childFragment.setOrder(order)
    }
  }

  private fun setSelectedTab(source: ProductsFragment.Source) {
    val layout = layout!!
    (0 until layout.tabs.childCount).forEach { layout.tabs.getChildAt(it).isSelected = it == source.ordinal }
  }

  internal fun selectUpdates() = selectUpdatesInternal(true)

  private fun selectUpdatesInternal(allowSmooth: Boolean) {
    if (view != null) {
      val viewPager = viewPager
      viewPager?.setCurrentItem(ProductsFragment.Source.UPDATES.ordinal, allowSmooth && viewPager.isLaidOut)
    } else {
      needSelectUpdates = true
    }
  }

  private fun updateUpdateNotificationBlocker(activeSource: ProductsFragment.Source) {
    val blockerFragment = if (activeSource == ProductsFragment.Source.UPDATES) {
      productFragments.find { it.source == activeSource }
    } else {
      null
    }
    syncConnection.binder?.setUpdateNotificationBlocker(blockerFragment)
  }

  private fun updateCategory() {
    if (category.isNotEmpty() && categories.indexOf(category) < 0) {
      category = ""
    }
    layout?.categoryName?.text = category.nullIfEmpty() ?: getString(R.string.all_applications)
    layout?.categoryIcon?.visibility = if (categories.isEmpty()) View.GONE else View.VISIBLE
    productFragments.forEach { it.setCategory(category) }
    categoriesList?.adapter?.notifyDataSetChanged()
  }

  private fun animateCategoriesList() {
    val categoriesList = categoriesList!!
    val value = if (categoriesList.visibility != View.VISIBLE) 0f else
      categoriesList.height.toFloat() / (categoriesList.parent as View).height
    val target = if (showCategories) 1f else 0f
    categoriesAnimator?.cancel()
    categoriesAnimator = null

    if (value != target) {
      categoriesAnimator = ValueAnimator.ofFloat(value, target).apply {
        duration = (250 * abs(target - value)).toLong()
        interpolator = if (target >= 1f) AccelerateInterpolator(2f) else DecelerateInterpolator(2f)
        addUpdateListener {
          val newValue = animatedValue as Float
          categoriesList.apply {
            val height = ((parent as View).height * newValue).toInt()
            val visible = height > 0
            if ((visibility == View.VISIBLE) != visible) {
              visibility = if (visible) View.VISIBLE else View.GONE
            }
            if (layoutParams.height != height) {
              layoutParams.height = height
              requestLayout()
            }
          }
          if (target <= 0f && newValue <= 0f || target >= 1f && newValue >= 1f) {
            categoriesAnimator = null
          }
        }
        start()
      }
    }
  }

  private val pageChangeCallback = object: ViewPager2.OnPageChangeCallback() {
    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
      val layout = layout!!
      val fromCategories = ProductsFragment.Source.values()[position].categories
      val toCategories = if (positionOffset <= 0f) fromCategories else
        ProductsFragment.Source.values()[position + 1].categories
      val offset = if (fromCategories != toCategories) {
        if (fromCategories) 1f - positionOffset else positionOffset
      } else {
        if (fromCategories) 1f else 0f
      }
      (layout.tabs.background as TabsBackgroundDrawable)
        .update(position + positionOffset, layout.tabs.childCount)
      assert(layout.categoryLayout.childCount == 1)
      val child = layout.categoryLayout.getChildAt(0)
      val height = child.layoutParams.height
      assert(height > 0)
      val currentHeight = (offset * height).roundToInt()
      if (layout.categoryLayout.layoutParams.height != currentHeight) {
        layout.categoryLayout.layoutParams.height = currentHeight
        layout.categoryLayout.requestLayout()
      }
    }

    override fun onPageSelected(position: Int) {
      val source = ProductsFragment.Source.values()[position]
      updateUpdateNotificationBlocker(source)
      sortOrderMenu!!.first.isVisible = source.order
      syncRepositoriesMenuItem!!.setShowAsActionFlags(if (!source.order ||
        resources.configuration.screenWidthDp >= 480) MenuItem.SHOW_AS_ACTION_ALWAYS else 0)
      setSelectedTab(source)
      if (showCategories && !source.categories) {
        showCategories = false
      }
    }

    override fun onPageScrollStateChanged(state: Int) {
      layout!!.categoryChange.isEnabled = state != ViewPager2.SCROLL_STATE_DRAGGING &&
        ProductsFragment.Source.values()[viewPager!!.currentItem].categories
    }
  }

  private class TabsBackgroundDrawable(context: Context, private val rtl: Boolean): Drawable() {
    private val height = context.resources.sizeScaled(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = context.getColorFromAttr(android.R.attr.colorAccent).defaultColor
    }

    private var position = 0f
    private var total = 0

    fun update(position: Float, total: Int) {
      this.position = position
      this.total = total
      invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
      if (total > 0) {
        val bounds = bounds
        val width = bounds.width() / total.toFloat()
        val x = width * position
        if (rtl) {
          canvas.drawRect(bounds.right - width - x, (bounds.bottom - height).toFloat(),
            bounds.right - x, bounds.bottom.toFloat(), paint)
        } else {
          canvas.drawRect(bounds.left + x, (bounds.bottom - height).toFloat(),
            bounds.left + x + width, bounds.bottom.toFloat(), paint)
        }
      }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
  }

  private class CategoriesAdapter(private val categories: () -> List<String>, private val onClick: (String) -> Unit):
    EnumRecyclerAdapter<CategoriesAdapter.ViewType, RecyclerView.ViewHolder>() {
    enum class ViewType { CATEGORY }

    private class CategoryViewHolder(context: Context): RecyclerView.ViewHolder(TextView(context)) {
      val title: TextView
        get() = itemView as TextView

      init {
        itemView as TextView
        itemView.gravity = Gravity.CENTER_VERTICAL
        itemView.resources.sizeScaled(16).let { itemView.setPadding(it, 0, it, 0) }
        itemView.setTextColor(context.getColorFromAttr(android.R.attr.textColorPrimary))
        itemView.setTextSizeScaled(16)
        itemView.background = context.getDrawableFromAttr(android.R.attr.selectableItemBackground)
        itemView.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
          itemView.resources.sizeScaled(48))
      }
    }

    override val viewTypeClass: Class<ViewType>
      get() = ViewType::class.java

    override fun getItemCount(): Int = 1 + categories().size
    override fun getItemEnumViewType(position: Int): ViewType = ViewType.CATEGORY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: ViewType): RecyclerView.ViewHolder {
      return CategoryViewHolder(parent.context).apply {
        itemView.setOnClickListener { onClick(categories().getOrNull(adapterPosition - 1).orEmpty()) }
      }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      holder as CategoryViewHolder
      holder.title.text = categories().getOrNull(position - 1)
        ?: holder.itemView.resources.getString(R.string.all_applications)
    }
  }
}
