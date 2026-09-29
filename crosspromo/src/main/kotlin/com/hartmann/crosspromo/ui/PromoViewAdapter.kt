package com.hartmann.crosspromo.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.R
import com.hartmann.crosspromo.launcher.PlayStoreLauncher
import com.hartmann.crosspromo.model.PromoApp
import kotlinx.coroutines.launch

/**
 * XML/View-system adapter so non-Compose apps can embed cross promotion with one
 * widget. Compose remains the primary UI; this mirrors it via RecyclerView.
 *
 * ```xml
 * <com.hartmann.crosspromo.ui.HartmannPromoRecyclerView
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:placement="settings"
 *     app:maxCards="3" />
 * ```
 */
class HartmannPromoRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var placement: String = "default"
    private var sectionLabel: String = "More from Hartmann Studios"
    private var maxCards: Int = 3

    private val adapter = PromoListAdapter(::onAppClicked, ::onAppImpression)

    init {
        orientation = VERTICAL
        context.obtainStyledAttributes(attrs, R.styleable.HartmannPromoRecyclerView).apply {
            placement = getString(R.styleable.HartmannPromoRecyclerView_placement) ?: placement
            sectionLabel = getString(R.styleable.HartmannPromoRecyclerView_sectionLabel) ?: sectionLabel
            maxCards = getInt(R.styleable.HartmannPromoRecyclerView_maxCards, maxCards)
            recycle()
        }

        val label = TextView(context).apply {
            text = sectionLabel
            textSize = 18f
            setPadding(paddingLeft + 48, paddingTop + 24, paddingRight + 16, paddingBottom + 8)
        }
        addView(label)

        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@HartmannPromoRecyclerView.adapter
        }
        addView(recycler)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner: LifecycleOwner? = findViewTreeLifecycleOwner()
        owner?.lifecycleScope?.launch {
            HartmannCrossPromo.loadPlacement(placement, maxCards) { state ->
                post { adapter.submitList(state.apps.take(maxCards), state.requestId) }
            }
        }
    }

    private fun onAppClicked(app: PromoApp, position: Int, requestId: String?) {
        HartmannCrossPromo.click(placement, app, app.selectionType, position + 1, requestId)
        PlayStoreLauncher.openPlayStore(
            context,
            app.packageName,
            referrer = PlayStoreLauncher.buildReferrer(HartmannCrossPromo.sourcePackage, app.packageName),
        )
    }

    private fun onAppImpression(app: PromoApp, position: Int, requestId: String?) {
        HartmannCrossPromo.impression(placement, app, app.selectionType, position + 1, requestId)
    }
}

/** Simple adapter over promo apps with per-item impression dedup. */
private class PromoListAdapter(
    private val onClick: (PromoApp, Int, String?) -> Unit,
    private val onImpression: (PromoApp, Int, String?) -> Unit,
) : RecyclerView.Adapter<PromoListAdapter.VH>() {

    private var items: List<PromoApp> = emptyList()
    private var requestId: String? = null
    private val impressionSent = mutableSetOf<String>()

    fun submitList(list: List<PromoApp>, requestId: String?) {
        items = list
        this.requestId = requestId
        notifyDataSetChanged()
    }

    class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val icon = ImageView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(112, 112)
        }
        val title = TextView(ctx)
        val rating = TextView(ctx)
        val cta = Button(ctx).apply { text = "View app" }
        val textColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 0, 0)
            addView(title)
            addView(rating)
            addView(cta)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(icon)
            addView(textColumn)
        }
        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        val textColumn = holder.root.getChildAt(1) as LinearLayout
        val title = textColumn.getChildAt(0) as TextView
        val rating = textColumn.getChildAt(1) as TextView
        val cta = textColumn.getChildAt(2) as Button
        val icon = holder.root.getChildAt(0) as ImageView

        title.text = app.name
        rating.text = app.ratingLabel ?: ""
        holder.root.setOnClickListener { onClick(app, holder.bindingAdapterPosition, requestId) }
        cta.setOnClickListener { onClick(app, holder.bindingAdapterPosition, requestId) }
        holder.root.contentDescription = "View ${app.name} on Google Play"

        if (impressionSent.add(app.packageName)) {
            onImpression(app, position, requestId)
        }

        app.iconUrl?.let { url ->
            val request = ImageRequest.Builder(holder.root.context)
                .data(url)
                .target(icon)
                .build()
            holder.root.context.imageLoader.enqueue(request)
        }
    }

    override fun getItemCount(): Int = items.size
}
