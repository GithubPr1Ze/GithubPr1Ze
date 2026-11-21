package fr.tachoperso.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import fr.tachoperso.core.rules.ExpenseKind
import fr.tachoperso.core.rules.MonthlyReport
import fr.tachoperso.core.rules.hm
import java.io.OutputStream
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Récapitulatif mensuel en PDF (A4) : heures, semaines, frais de route, détail par jour, constats.
 * Le dessin passe par [Pager] pour pouvoir aussi être rendu en image (tests).
 */
object ReportPdf {
    const val W = 595
    const val H = 842
    private const val M = 40f
    private val fr = Locale.FRANCE
    private val euro = NumberFormat.getCurrencyInstance(fr)
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", fr)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE dd/MM", fr)
    private val shortFmt = DateTimeFormatter.ofPattern("dd/MM", fr)
    private val genFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", fr)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", fr)
    private val paris = java.time.ZoneId.of("Europe/Paris")
    private const val ACCENT = 0xFF1F3A5F.toInt()
    private const val MUTED = 0xFF5F6670.toInt()
    private const val RULE = 0xFFD5D9DF.toInt()

    fun fileName(r: MonthlyReport): String = "TachoPerso_recap_${r.month}.pdf"

    fun write(out: OutputStream, r: MonthlyReport, driver: String, cardNumber: String, bareme: String) {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        val pager = Pager(
            newPage = { n -> page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); page!!.canvas },
            endPage = { doc.finishPage(page) },
        )
        draw(pager, r, driver, cardNumber, bareme)
        doc.writeTo(out)
        doc.close()
    }

    fun draw(p: Pager, r: MonthlyReport, driver: String, cardNumber: String, bareme: String) {
        val title = monthFmt.format(r.month).replaceFirstChar { it.titlecase(fr) }
        p.start()
        p.text("RÉCAPITULATIF MENSUEL", 9f, MUTED, bold = true)
        p.text(title, 22f, ACCENT, bold = true, gap = 4f)
        p.text("$driver  ·  carte n° $cardNumber", 10f)
        p.text("Heure de Paris  ·  généré le ${genFmt.format(LocalDateTime.now())}  ·  Tacho Perso", 8f, MUTED, gap = 10f)

        p.heading("Synthèse")
        val e = r.expenses
        p.grid(
            listOf(
                "Jours travaillés" to "${r.workedDays}",
                "Distance" to "%,d km".format(fr, r.km),
                "Conduite" to hm(r.driving),
                "Autres tâches" to hm(r.work),
                "Disponibilité" to hm(r.availability),
                "Temps de service" to hm(r.service),
                "Heures de nuit (21 h – 6 h)" to hm(r.nightMinutes),
                "Découchés" to "${r.decouchers}" + if (r.nightsToClarify > 0) " (+${r.nightsToClarify} à préciser)" else "",
                "Ferry / train" to (r.ferry?.let { "${hm(it.minutes)} (dont ${hm(it.restMinutes)} en repos)" } ?: "—"),
                "Frais de route" to euro.format(e?.total ?: 0.0),
            ),
        )

        p.heading("Semaines (lundi – dimanche)")
        val wk = floatArrayOf(150f, 85f, 85f, 95f, 100f)
        p.row(listOf("Semaine du", "Conduite", "Service", "Au-delà de 35 h", "Au-delà de 43 h"), wk, header = true)
        r.weeks.forEach { w ->
            p.row(listOf("${shortFmt.format(w.monday)} au ${shortFmt.format(w.monday.plusDays(6))}", hm(w.driving), hm(w.service), hm(w.over35), hm(w.over43)), wk)
        }
        p.note("Semaines entières, y compris les jours hors du mois. 43 h : durée d'équivalence des grands routiers.")

        val am = r.amplitude
        p.heading("Amplitude des journées de travail")
        p.grid(
            listOf(
                "Journées" to "${am.count}",
                "Amplitude totale" to hm(am.gross),
                "Coupures retirées" to hm(am.deductions),
                "Amplitude − coupure" to hm(am.net),
                "Moyenne par journée" to hm(am.average),
                "Plus longue journée" to (am.longest?.let { "${hm(it.amplitude)} (${dayFmt.format(it.date)})" } ?: "—"),
            ),
        )
        if (am.days.isNotEmpty()) {
            val ak = floatArrayOf(90f, 130f, 75f, 75f, 70f, 75f)
            p.row(listOf("Prise de poste", "Horaires", "Conduite", "Amplitude", "Coupure", "Retenu"), ak, header = true, size = 8f)
            am.days.forEach { d ->
                p.row(
                    listOf(
                        dayFmt.format(d.date),
                        "${timeFmt.format(d.shift.start.atZone(paris))} – ${timeFmt.format(d.shift.end.atZone(paris))}" + if (d.inProgress) " (en cours)" else "",
                        hm(d.driving), hm(d.amplitude), if (d.deduction > 0) "−${hm(d.deduction)}" else "", hm(d.net),
                    ),
                    ak, size = 8f,
                )
            }
        }
        p.note("Amplitude = prise → fin de poste (journée rattachée au jour de prise de poste). 45 min de coupure retirées si la conduite dépasse 4 h 30 ou l'amplitude 6 h.")

        p.heading("Frais de route")
        val fk = floatArrayOf(330f, 60f, 125f)
        p.row(listOf("Indemnité", "Nombre", "Montant"), fk, header = true)
        ExpenseKind.entries.forEach { k ->
            val n = e?.count(k) ?: 0
            if (n > 0) p.row(listOf(k.label, "$n", euro.format(e!!.days.flatMap { it.items }.filter { it.kind == k }.sumOf { it.amount })), fk)
        }
        p.row(listOf("Total", "", euro.format(e?.total ?: 0.0)), fk, bold = true)
        p.note("Barème : $bareme. Protocole du 30/04/1974 (CCN transports routiers, IDCC 16). Estimation indicative.")

        p.heading("Détail par jour")
        val dk = floatArrayOf(62f, 44f, 40f, 40f, 40f, 52f, 54f, 183f)
        p.row(listOf("Date", "Cond.", "Trav.", "Dispo", "Km", "Nuit", "Frais", "Indemnités"), dk, header = true, size = 8f)
        r.days.forEach { d ->
            val worked = d.driving + d.work + d.availability > 0
            p.row(
                listOf(
                    dayFmt.format(d.date),
                    if (worked) hm(d.driving) else "",
                    if (worked) hm(d.work) else "",
                    if (worked) hm(d.availability) else "",
                    if (d.km > 0) "${d.km}" else "",
                    if (d.away) "découché" else "",
                    if (d.expenses > 0) euro.format(d.expenses) else "",
                    d.expenseText.ifBlank { if (worked) "" else "repos" },
                ),
                dk, size = 8f,
            )
        }

        p.heading("Constats réglementaires")
        if (r.findings.isEmpty()) p.text("Aucun constat sur le mois.", 9f)
        r.findings.forEach { f ->
            p.text("${dayFmt.format(f.at.atZone(java.time.ZoneId.of("Europe/Paris")))} — ${f.rule.title} : ${f.observed} (limite ${f.limit}) — ${f.rule.article}", 8.5f, wrap = true)
        }
        p.note("Contrôle indicatif selon le règlement (CE) 561/2006 et le Code des transports. Seuls les textes officiels font foi.")
        p.end()
    }

    /** Mise en page simple : curseur vertical, sauts de page automatiques, pied de page numéroté. */
    class Pager(private val newPage: (Int) -> Canvas, private val endPage: () -> Unit) {
        private lateinit var c: Canvas
        private var y = 0f
        private var n = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun start() = open()

        private fun open() {
            n++
            c = newPage(n)
            c.drawColor(Color.WHITE)
            y = M
        }

        private fun footer() {
            setPaint(7.5f, MUTED, false)
            c.drawText("Tacho Perso — récapitulatif indicatif, seuls les textes officiels et votre bulletin de paie font foi.", M, H - 22f, paint)
            val s = "page $n"
            c.drawText(s, W - M - paint.measureText(s), H - 22f, paint)
        }

        fun end() { footer(); endPage() }

        private fun need(h: Float) {
            if (y + h > H - 46f) { footer(); endPage(); open() }
        }

        private fun setPaint(size: Float, color: Int, bold: Boolean) {
            paint.textSize = size
            paint.color = color
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        fun text(s: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false, gap: Float = 2f, wrap: Boolean = false) {
            setPaint(size, color, bold)
            val lines = if (wrap) wrapLines(s, W - 2 * M) else listOf(s)
            lines.forEach { line ->
                need(size * 1.35f)
                y += size * 1.1f
                c.drawText(line, M, y, paint)
                y += size * 0.25f
            }
            y += gap
        }

        fun note(s: String) = text(s, 7.5f, MUTED, gap = 6f, wrap = true)

        fun heading(s: String) {
            need(40f)
            y += 10f
            text(s.uppercase(fr), 9f, ACCENT, bold = true, gap = 0f)
            paint.color = ACCENT
            c.drawRect(M, y + 1f, W - M, y + 2f, paint)
            y += 8f
        }

        fun grid(items: List<Pair<String, String>>) {
            val colW = (W - 2 * M) / 2
            items.chunked(2).forEach { pair ->
                need(28f)
                pair.forEachIndexed { i, (k, v) ->
                    val x = M + i * colW
                    setPaint(7.5f, MUTED, false); c.drawText(k, x, y + 8f, paint)
                    setPaint(12f, Color.BLACK, true); c.drawText(v, x, y + 22f, paint)
                }
                y += 28f
            }
            y += 4f
        }

        fun row(cols: List<String>, widths: FloatArray, header: Boolean = false, bold: Boolean = false, size: Float = 9f) {
            val h = size * 1.75f
            need(h)
            var x = M
            cols.forEachIndexed { i, s ->
                setPaint(if (header) size - 1 else size, if (header) MUTED else Color.BLACK, header || bold)
                val w = widths.getOrElse(i) { 60f } - 4f
                val shown = if (paint.measureText(s) > w) ellipsize(s, w) else s
                c.drawText(shown, x, y + size * 1.15f, paint)
                x += widths.getOrElse(i) { 60f }
            }
            y += h
            paint.color = RULE
            c.drawRect(M, y - 1f, W - M, y - 0.5f, paint)
        }

        private fun ellipsize(s: String, w: Float): String {
            var t = s
            while (t.isNotEmpty() && paint.measureText("$t…") > w) t = t.dropLast(1)
            return "$t…"
        }

        private fun wrapLines(s: String, w: Float): List<String> {
            val out = mutableListOf<String>()
            var line = ""
            for (word in s.split(" ")) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(cand) > w && line.isNotEmpty()) { out += line; line = word } else line = cand
            }
            if (line.isNotEmpty()) out += line
            return out
        }
    }
}
