package com.silverajat.lifttracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.silverajat.lifttracker.MainActivity
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.Energy
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.meals.MealEntry
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the widget shows, saved whenever the analysis runs (the widget reads this file). */
data class WidgetData(
    val date: String,
    val recovery: Int?, val zone: String?,
    val strain: Double?, val strainTarget: String?,
    val sleepPct: Int?, val sleepMin: Int?,
    val net: Int?, val netGoal: Int?,
    val updated: String,
    val steps: Long? = null,
    val load: Int? = null, val loadLow: Int? = null, val loadHigh: Int? = null,
) {
    fun json(): String = JSONObject().apply {
        put("date", date); put("updated", updated)
        recovery?.let { put("recovery", it) }; zone?.let { put("zone", it) }
        strain?.let { put("strain", it) }; strainTarget?.let { put("strainTarget", it) }
        sleepPct?.let { put("sleepPct", it) }; sleepMin?.let { put("sleepMin", it) }
        net?.let { put("net", it) }; netGoal?.let { put("netGoal", it) }
        steps?.let { put("steps", it) }
        load?.let { put("load", it) }; loadLow?.let { put("loadLow", it) }; loadHigh?.let { put("loadHigh", it) }
    }.toString()

    companion object {
        private fun file(ctx: Context) = File(ctx.filesDir, "widget.json")

        fun load(ctx: Context): WidgetData? = runCatching {
            val o = JSONObject(file(ctx).readText())
            fun int(k: String) = if (o.has(k)) o.getInt(k) else null
            WidgetData(o.getString("date"), int("recovery"), o.optString("zone").ifEmpty { null },
                if (o.has("strain")) o.getDouble("strain") else null, o.optString("strainTarget").ifEmpty { null },
                int("sleepPct"), int("sleepMin"), int("net"), int("netGoal"), o.getString("updated"),
                if (o.has("steps")) o.getLong("steps") else null, int("load"), int("loadLow"), int("loadHigh"))
        }.getOrNull()

        fun from(a: Analysis.Result, todayEntries: List<MealEntry>, today: LocalDate, now: LocalTime, days: List<HealthDay> = emptyList()): WidgetData {
            val d = a.today
            val t = if (todayEntries.isNotEmpty()) Energy.today(a.energy, todayEntries, today, now) else null
            val wl = com.silverajat.lifttracker.ui.weekLoad(a)
            return WidgetData(
                date = today.toString(),
                recovery = d?.recovery?.score, zone = d?.recovery?.zone?.name,
                strain = d?.strain?.strain,
                strainTarget = d?.recovery?.let { Analysis.strainTarget(it.zone) }?.let { "${it.start.toInt()}-${it.endInclusive.toInt()}" },
                sleepPct = d?.sleep?.performance, sleepMin = d?.sleep?.asleepMin,
                net = t?.endOfDay, netGoal = a.energy.netFor(a.goalKgPerWeek),
                updated = "%d:%02d".format(now.hour, now.minute),
                steps = days.lastOrNull { it.date == today }?.steps,
                load = if (days.isNotEmpty()) wl.load else null, loadLow = wl.low, loadHigh = wl.high,
            )
        }

        /** Save and redraw every placed widget. */
        suspend fun publish(ctx: Context, w: WidgetData) {
            runCatching { file(ctx).writeText(w.json()) }
            runCatching { HubWidget().updateAll(ctx) }
        }
    }
}

class HubWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val w = WidgetData.load(context)?.takeIf { it.date == LocalDate.now().toString() }
        provideContent { Content(w) }
    }

    private val bg = Color(0xFF1A1F27)
    private val tile = Color(0xFF222832)
    private val muted = Color(0xFF8F98A6)
    private val green = Color(0xFF30B566)
    private val yellow = Color(0xFFF5C542)
    private val red = Color(0xFFE5484D)
    private val blue = Color(0xFF3E7BFA)
    private val chalk = Color(0xFFECEEE8)

    @Composable
    private fun Content(w: WidgetData?) {
        Column(
            GlanceModifier.fillMaxSize().background(bg).cornerRadius(22.dp).padding(6.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                val rc = when (w?.zone) { "GREEN" -> green; "YELLOW" -> yellow; "RED" -> red; else -> muted }
                Tile("Recovery", w?.recovery?.let { "$it%" }, w?.zone?.let { z -> Analysis.Zone.valueOf(z).label }, rc)
                Gap()
                Tile("Strain", w?.strain?.let { "%.1f".format(it) }, w?.strainTarget?.let { "aim $it" }, blue)
                Gap()
                Tile("Sleep", w?.sleepMin?.let { "${it / 60}h${"%02d".format(it % 60)}" }, w?.sleepPct?.let { "$it% of need" }, chalk)
            }
            Spacer(GlanceModifier.height(5.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                val nc = if (w?.net == null || w.netGoal == null) muted
                    else if (abs(w.net - w.netGoal) <= 150) green else if (abs(w.net - w.netGoal) <= 400) yellow else red
                Tile("Net kcal", w?.net?.let { (if (it > 0) "+" else if (it < 0) "−" else "") + "%,d".format(abs(it)) },
                    w?.netGoal?.let { "goal " + (if (it > 0) "+" else "") + it } ?: "log food", nc)
                Gap()
                Tile("Steps", w?.steps?.let { if (it >= 10_000) "%.1fk".format(it / 1000.0) else "%,d".format(it) },
                    w?.steps?.let { if (it >= 10_000) "10k done" else "${((10_000 - it) / 100.0).roundToInt() / 10.0}k to go" }, if ((w?.steps ?: 0) >= 10_000) green else chalk)
                Gap()
                val lc = if (w?.load == null || w.loadLow == null || w.loadHigh == null) blue
                    else if (w.load in w.loadLow..w.loadHigh) green else if (w.load > w.loadHigh) yellow else muted
                Tile("Week load", w?.load?.toString(), if (w?.loadLow != null) "range ${w.loadLow}-${w.loadHigh}" else "7 days", lc)
            }
        }
    }

    @Composable
    private fun Gap() = Spacer(GlanceModifier.width(5.dp))

    /** One metric. Width comes from its weight in the row (fillMaxSize would push the others off). */
    @Composable
    private fun RowScope.Tile(label: String, value: String?, sub: String?, color: Color) {
        Column(
            GlanceModifier.defaultWeight().fillMaxHeight().background(tile).cornerRadius(14.dp).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TextStyle(color = ColorProvider(muted), fontSize = 10.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(value ?: "–", style = TextStyle(color = ColorProvider(if (value == null) muted else color), fontSize = 19.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (sub != null) Text(sub, style = TextStyle(color = ColorProvider(muted), fontSize = 9.sp), maxLines = 1)
        }
    }
}

class HubWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HubWidget()
}
