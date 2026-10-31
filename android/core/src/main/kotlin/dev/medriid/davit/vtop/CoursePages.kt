package dev.medriid.davit.vtop

import dev.medriid.davit.vtop.Html.clean
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Course materials (the consolidated course page) and the assignment upload pages. */
object CoursePages {

    /**
     * `academics/common/CoursePageConsolidated`: `<select id=courseId>`, each option
     * "Winter Semester 2026-27 - BACHI101 - Chinese Level I - ETH - Embedded Theory - 2.0"
     * with `data-courseid`, `data-crstype` and `data-semestr`.
     */
    fun courses(html: String): List<CourseOption> =
        Jsoup.parse(html).select("select#courseId option, select[name=courseId] option").mapNotNull { o ->
            val id = o.attr("data-courseid").ifEmpty { o.attr("value") }.trim()
            if (id.isEmpty()) return@mapNotNull null
            val parts = clean(o.text()).split(" - ").map { it.trim() }
            // semester - code - name (may hold " - ") - type - type name - credits
            val tail = if (parts.lastOrNull()?.toDoubleOrNull() != null) parts.dropLast(1) else parts
            val type = o.attr("data-crstype").ifEmpty { tail.getOrElse(tail.size - 2) { "" } }.trim()
            val typeName = tail.lastOrNull().orEmpty()
            val name = if (tail.size >= 5) tail.subList(2, tail.size - 2).joinToString(" - ") else tail.getOrElse(2) { "" }
            CourseOption(
                courseId = id,
                semesterId = o.attr("data-semestr").trim(),
                type = type,
                semesterName = parts.firstOrNull().orEmpty(),
                code = parts.getOrElse(1) { id.split('_').getOrElse(1) { id } },
                name = name,
                typeName = typeName,
            )
        }.distinctBy { it.key }

    /**
     * `academics/CoursePageConsolidated/getCourseDetail`: the syllabus table's module cells
     * ("1 - Chemical thermodynamics and kinetics - CO: 1") and `#materialTable`, a row per
     * file: semester + course, "title / module no / (Document)", "empId - NAME - SCHOOL" +
     * date, and a button carrying `data-fileid`.
     */
    fun materials(html: String): CourseMaterials {
        val doc = Jsoup.parse(html)
        val modules = doc.select("table").firstOrNull { it.selectFirst("tr")?.text()?.contains("Module", ignoreCase = true) == true && "Topic" in it.text() }
            ?.select("td[rowspan]").orEmpty()
            .mapNotNull { td ->
                val text = clean(td.text())
                val no = text.substringBefore(" - ").trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapNotNull null
                no to text.substringAfter(" - ").replace(Regex("""\s*-\s*CO\s*:.*$""", RegexOption.IGNORE_CASE), "").trim()
            }.toMap()
        val table = doc.getElementById("materialTable")
            ?: doc.select("table").firstOrNull { "Material Detail" in it.text() }
            ?: return CourseMaterials(modules, emptyList())
        val materials = table.select("tr").mapNotNull { tr ->
            val fileId = tr.selectFirst("[data-fileid]")?.attr("data-fileid")?.trim().orEmpty()
            if (fileId.isEmpty()) return@mapNotNull null
            val tds = tr.select("> td")
            if (tds.size < 4) return@mapNotNull null
            val course = spans(tds[1])
            val detail = spans(tds[2])
            val by = spans(tds[3])
            val uploader = by.firstOrNull().orEmpty().split(" - ").map { it.trim() }
            val hasId = uploader.size >= 2 && uploader[0].any(Char::isDigit)
            Material(
                fileId = fileId,
                title = detail.getOrElse(0) { clean(tds[2].text()) },
                module = detail.drop(1).firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }.orEmpty(),
                kind = detail.lastOrNull { it.startsWith("(") }?.trim('(', ')', ' ').orEmpty(),
                uploaderId = if (hasId) uploader[0] else "",
                uploader = (if (hasId) uploader.drop(1) else uploader).let { if (it.size >= 2) it.dropLast(1) else it }.joinToString(" - "),
                school = if (uploader.size >= 3) uploader.last() else "",
                date = by.firstOrNull { Regex("""\d{2}-\d{2}-\d{4}""").matches(it) }.orEmpty(),
                semester = course.firstOrNull().orEmpty(),
            )
        }
        return CourseMaterials(modules, materials)
    }

    private fun spans(td: Element): List<String> =
        td.select("span").map { clean(it.text()) }.filter { it.isNotEmpty() }.ifEmpty { listOf(clean(td.text())) }

    // ---- assignment upload -----------------------------------------------------------

    /**
     * `examinations/processDigitalAssignmentUpload`: the course page again, now with the
     * `#daUpload` form holding a file input. Every field goes back as rendered. A page
     * without the file input says why (red text) and is a [Refused].
     */
    fun uploadForm(html: String, classId: String, code: String): UploadForm {
        val doc = Jsoup.parse(html)
        val form = doc.getElementById("daUpload")?.takeIf { it.selectFirst("input[type=file]") != null }
            ?: doc.select("form").firstOrNull { it.selectFirst("input[type=file]") != null }
            ?: throw Refused(Feeds.warnings(doc) ?: "VTOP didn't open the upload form for this assignment.")
        val file = form.selectFirst("input[type=file]")!!
        return UploadForm(classId, code, Html.formFields(form), file.attr("name").ifEmpty { "studDaUpload" }, Html.fieldsBefore(form, file))
    }

    /**
     * What came back from `doDAssignmentUploadMethod` or `doDAssignmentOtpUpload`:
     * "Uploaded successfully" anywhere means done; an OTP form (`#daUploadOtpAlert`,
     * field `otpEmail`) means a replacement is waiting for the emailed code; anything else
     * is VTOP's message (the `message`/`error` headers first, then the page's red text).
     */
    fun uploadOutcome(html: String, code: String, errorHeader: String? = null, messageHeader: String? = null): UploadOutcome {
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        val text = clean(doc.text())
        if (text.contains("uploaded successfully", ignoreCase = true)) {
            return UploadOutcome.Uploaded(Parsers.assignments(html).firstOrNull { it.code == code }?.updated)
        }
        val otp = doc.getElementById("daUploadOtpAlert") ?: doc.selectFirst("input[name=otpEmail]")?.closest("form")
        if (otp != null) {
            val input = otp.selectFirst("input[name=otpEmail]")
                ?: otp.select("input").firstOrNull { it.attr("type").lowercase() in setOf("", "text", "number", "password", "tel") }
            val name = input?.attr("name")?.ifEmpty { null } ?: "otpEmail"
            val said = Feeds.warnings(doc)?.takeIf { w ->
                listOf("invalid", "incorrect", "wrong", "expired", "mismatch", "not valid").any { w.contains(it, ignoreCase = true) }
            }
            return UploadOutcome.NeedsOtp(Feeds.maskedEmail(text), Html.formFields(otp).filter { it.first != name }, name, said)
        }
        val header = listOfNotNull(messageHeader, errorHeader).map { it.trim() }.firstOrNull { it.isNotEmpty() && it.toIntOrNull() == null }
        return UploadOutcome.Refused(header ?: Feeds.warnings(doc) ?: "VTOP didn't confirm the upload. Check the assignment on VTOP.")
    }
}
