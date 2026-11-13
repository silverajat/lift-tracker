package com.silverajat.lifttracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import com.silverajat.lifttracker.meals.MealEntry
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** What the widget shows, saved whenever the analysis runs (the widget reads this file). */
data class WidgetData(
    val date: String,
    val recovery: Int?, val zone: String?,
    val strain: Double?, val strainTarget: String?,
    val sleepPct: Int?, val sleepMin: Int?,
    val net: Int?, val netGoal: Int?,
    val updated: String,
) {
    fun json(): String = JSONObject().apply {
        put("date", date); put("updated", updated)
        recovery?.let { put("recovery", it) }; zone?.let { put("zone", it) }
        strain?.let { put("strain", it) }; strainTarget?.let { put("strainTarget", it) }
        sleepPct?.let { put("sleepPct", it) }; sleepMin?.let { put("sleepMin", it) }
        net?.let { put("net", it) }; netGoal?.let { put("netGoal", it) }
    }.toString()

    companion object {
        private fun file(ctx: Context) = File(ctx.filesDir, "widget.json")

        fun load(ctx: Context): WidgetData? = runCatching {
            val o = JSONObject(file(ctx).readText())
            fun int(k: String) = if (o.has(k)) o.getInt(k) else null
            WidgetData(o.getString("date"), int("recovery"), o.optString("zone").ifEmpty { null },
                if (o.has("strain")) o.getDouble("strain") else null, o.optString("strainTarget").ifEmpty { null },
                int("sleepPct"), int("sleepMin"), int("net"), int("netGoal"), o.getString("updated"))
        }.getOrNull()

        fun from(a: Analysis.Result, todayEntries: List<MealEntry>, today: LocalDate, now: LocalTime): WidgetData {
            val d = a.today
            val t = if (todayEntries.isNotEmpty()) Energy.today(a.energy, todayEntries, today, now) else null
            return WidgetData(
                date = today.toString(),
                recovery = d?.recovery?.score, zone = d?.recovery?.zone?.name,
                strain = d?.strain?.strain,
                strainTarget = d?.recovery?.let { Analysis.strainTarget(it.zone) }?.let { "${it.start.toInt()}-${it.endInclusive.toInt()}" },
                sleepPct = d?.sleep?.performance, sleepMin = d?.sleep?.asleepMin,
                net = t?.endOfDay, netGoal = a.energy.netFor(a.goalKgPerWeek),
                updated = "%d:%02d".format(now.hour, now.minute),
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

    private val bg = Color(0xFF0B0D10)
    private val tile = Color(0xFF15181D)
    private val muted = Color(0xFF8B93A1)
    private val green = Color(0xFF16EC06)
    private val yellow = Color(0xFFFFDE00)
    private val red = Color(0xFFFF0026)
    private val blue = Color(0xFF0093E7)
    private val purple = Color(0xFF7BA1FF)

    @Composable
    private fun Content(w: WidgetData?) {
        Column(
            GlanceModifier.fillMaxSize().background(bg).cornerRadius(22.dp).padding(8.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                val rc = when (w?.zone) { "GREEN" -> green; "YELLOW" -> yellow; "RED" -> red; else -> muted }
                Tile("RECOVERY", w?.recovery?.let { "$it%" }, w?.zone?.let { z -> Analysis.Zone.valueOf(z).label }, rc, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                Tile("STRAIN", w?.strain?.let { "%.1f".format(it) }, w?.strainTarget?.let { "target $it" }, blue, GlanceModifier.defaultWeight())
            }
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                Tile("SLEEP", w?.sleepPct?.let { "$it%" }, w?.sleepMin?.let { "${it / 60}h ${it % 60}m" }, purple, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                val nc = if (w?.net == null || w.netGoal == null) muted
                    else if (kotlin.math.abs(w.net - w.netGoal) <= 150) green
                    else if (kotlin.math.abs(w.net - w.netGoal) <= 400) yellow else red
                Tile("NET KCAL", w?.net?.let { (if (it > 0) "+" else if (it < 0) "−" else "") + "%,d".format(kotlin.math.abs(it)) },
                    w?.netGoal?.let { "goal " + (if (it > 0) "+" else "") + it } ?: "log food", nc, GlanceModifier.defaultWeight())
            }
        }
    }

    @Composable
    private fun Tile(label: String, value: String?, sub: String?, color: Color, modifier: GlanceModifier) {
        Column(
            modifier.fillMaxSize().background(tile).cornerRadius(16.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TextStyle(color = ColorProvider(muted), fontSize = 10.sp, fontWeight = FontWeight.Medium))
            Text(value ?: "–", style = TextStyle(color = ColorProvider(if (value == null) muted else color), fontSize = 22.sp, fontWeight = FontWeight.Bold))
            if (sub != null) Text(sub, style = TextStyle(color = ColorProvider(muted), fontSize = 10.sp), maxLines = 1)
        }
    }
}

class HubWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HubWidget()
}
