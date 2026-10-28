package dev.medriid.davit.data

import dev.medriid.davit.vtop.Assignment
import dev.medriid.davit.vtop.AttendanceDay
import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.DetailKey
import dev.medriid.davit.vtop.CourseAssignments
import dev.medriid.davit.vtop.CourseMarks
import dev.medriid.davit.vtop.Exam
import dev.medriid.davit.vtop.FacultyInfo
import dev.medriid.davit.vtop.GradeEntry
import dev.medriid.davit.vtop.GradeHistory
import dev.medriid.davit.vtop.HostelInfo
import dev.medriid.davit.vtop.HostelRequests
import dev.medriid.davit.vtop.Leave
import dev.medriid.davit.vtop.NightSlip
import dev.medriid.davit.vtop.Profile
import dev.medriid.davit.vtop.MarkItem
import dev.medriid.davit.vtop.Semester
import dev.medriid.davit.vtop.TimetableCourse
import org.json.JSONArray
import org.json.JSONObject

/** Everything the screens show, as of the last sync. Cached encrypted on the phone. */
data class Snapshot(
    val semester: Semester,
    val semesters: List<Semester>,
    val attendance: List<AttendanceRow>,
    val timetable: List<TimetableCourse>,
    val syncedAt: Long,
    /** Human-readable differences from the previous sync, newest first. */
    val changes: List<String>,
    val marks: List<CourseMarks> = emptyList(),
    val exams: List<Exam> = emptyList(),
    /** Null until the first successful grade-history fetch. */
    val grades: GradeHistory? = null,
    val assignments: List<CourseAssignments> = emptyList(),
    /** Day-by-day attendance per course, keyed by [AttendanceRow.key]. */
    val history: Map<String, List<AttendanceDay>> = emptyMap(),
    val profile: Profile? = null,
    val profileAt: Long = 0,
    /** Only for hostellers. */
    val hostel: HostelRequests? = null,
    val hostelAt: Long = 0,
    /** The published academic calendar for [semester], if one exists. */
    val calendar: AcademicCalendar? = null,
    val calendarAt: Long = 0,
    /** Directory cards for this semester's faculty. */
    val faculty: List<FacultyInfo> = emptyList(),
    val facultyAt: Long = 0,
) {
    /** CGPA ≥ 9 is exempt from the 75% attendance rule. Unknown CGPA (first semester) isn't. */
    val attendanceExempt: Boolean get() = (grades?.cgpa ?: 0.0) >= 9.0

    fun toJson(): String = JSONObject().apply {
        put("v", 2)
        put("semester", sem(semester))
        put("semesters", JSONArray(semesters.map(::sem)))
        put("attendance", JSONArray(attendance.map {
            JSONObject().put("code", it.courseCode).put("name", it.courseName).put("type", it.classType)
                .put("attended", it.attended).put("total", it.total).put("pct", it.vtopPercent ?: -1)
                .put("classId", it.classId)
                .put("dSem", it.detail.semesterId).put("dReg", it.detail.regNo)
                .put("dCourse", it.detail.courseId).put("dType", it.detail.courseType)
        }))
        put("history", JSONObject().apply {
            history.forEach { (k, days) ->
                put(k, JSONArray(days.map { JSONObject().put("date", it.date).put("slot", it.slot).put("status", it.status.name) }))
            }
        })
        put("timetable", JSONArray(timetable.map {
            JSONObject().put("classId", it.classId).put("code", it.courseCode).put("name", it.courseName)
                .put("type", it.courseType).put("credits", it.credits).put("slot", it.slot)
                .put("venue", it.venue).put("faculty", it.faculty)
        }))
        put("syncedAt", syncedAt)
        put("changes", JSONArray(changes))
        put("marks", JSONArray(marks.map { c ->
            JSONObject().put("classId", c.classId).put("code", c.courseCode).put("name", c.courseName).put("type", c.courseType)
                .put("items", JSONArray(c.items.map { m ->
                    JSONObject().put("title", m.title).putNum("max", m.max).putNum("weightage", m.weightage)
                        .put("status", m.status).putNum("scored", m.scored).putNum("weighted", m.weighted).put("remark", m.remark)
                }))
        }))
        put("exams", JSONArray(exams.map {
            JSONObject().put("classId", it.classId).put("code", it.courseCode).put("name", it.courseName).put("type", it.type)
                .put("date", it.date).put("session", it.session).put("time", it.time).put("venue", it.venue).put("seat", it.seat)
        }))
        put("assignments", JSONArray(assignments.map { c ->
            JSONObject().put("classId", c.classId).put("code", c.courseCode).put("name", c.courseName).put("type", c.courseType)
                .put("items", JSONArray(c.items.map {
                    JSONObject().put("title", it.title).putNum("max", it.max).putNum("weightage", it.weightage)
                        .put("due", it.due).put("updated", it.updated ?: JSONObject.NULL)
                }))
        }))
        profile?.let { p ->
            put("profile", JSONObject().put("name", p.name).put("gender", p.gender).put("photo", p.photo ?: JSONObject.NULL)
                .put("hostel", p.hostel?.let { h -> JSONObject().put("block", h.block).put("room", h.room).put("bed", h.bed).put("mess", h.mess) } ?: JSONObject.NULL))
            put("profileAt", profileAt)
        }
        hostel?.let { h ->
            fun slips(l: List<NightSlip>) = JSONArray(l.map {
                JSONObject().put("venue", it.venue).put("purpose", it.purpose).put("to", it.appliedTo).put("from", it.from)
                    .put("until", it.to).put("time", it.time).put("status", it.status)
            })
            fun leaves(l: List<Leave>) = JSONArray(l.map {
                JSONObject().put("place", it.place).put("reason", it.reason).put("type", it.type).put("from", it.from)
                    .put("to", it.to).put("status", it.status)
            })
            put("hostel", JSONObject().put("activeSlips", slips(h.activeSlips)).put("pastSlips", slips(h.pastSlips))
                .put("activeLeave", leaves(h.activeLeave)).put("pastLeave", leaves(h.pastLeave)))
            put("hostelAt", hostelAt)
        }
        calendar?.let { put("calendar", it.toJson()).put("calendarAt", calendarAt) }
        put("faculty", JSONArray(faculty.map {
            JSONObject().put("id", it.empId).put("name", it.name).put("designation", it.designation).put("department", it.department)
                .put("school", it.school).put("email", it.email).put("cabin", it.cabin)
        }))
        put("facultyAt", facultyAt)
        grades?.let { g ->
            put("grades", JSONObject().putNum("cgpa", g.cgpa).putNum("earned", g.creditsEarned).put("entries", JSONArray(g.entries.map {
                JSONObject().put("code", it.courseCode).put("title", it.courseTitle).put("credits", it.credits)
                    .put("grade", it.grade ?: "").put("month", it.examMonth)
            })))
        }
    }.toString()

    companion object {
        private fun sem(s: Semester) = JSONObject().put("id", s.id).put("name", s.name)
        private fun semOf(o: JSONObject) = Semester(o.getString("id"), o.getString("name"))

        fun fromJson(text: String): Snapshot? = runCatching {
            val o = JSONObject(text)
            Snapshot(
                semester = semOf(o.getJSONObject("semester")),
                semesters = o.getJSONArray("semesters").objects().map(::semOf),
                attendance = o.getJSONArray("attendance").objects().map {
                    AttendanceRow(
                        it.getString("code"), it.getString("name"), it.getString("type"),
                        it.getInt("attended"), it.getInt("total"),
                        it.getInt("pct").takeIf { p -> p >= 0 }, it.getString("classId"),
                        DetailKey(it.optString("dSem"), it.optString("dReg"), it.optString("dCourse"), it.optString("dType")),
                    )
                },
                timetable = o.getJSONArray("timetable").objects().map {
                    TimetableCourse(
                        it.getString("classId"), it.getString("code"), it.getString("name"), it.getString("type"),
                        it.getDouble("credits"), it.getString("slot"), it.getString("venue"), it.getString("faculty"),
                    )
                },
                syncedAt = o.getLong("syncedAt"),
                changes = o.getJSONArray("changes").let { a -> List(a.length()) { a.getString(it) } },
                // v1 caches have none of these; they fill in on the next sync.
                marks = o.optJSONArray("marks")?.objects()?.map { c ->
                    CourseMarks(
                        c.getString("classId"), c.getString("code"), c.getString("name"), c.getString("type"),
                        c.getJSONArray("items").objects().map {
                            MarkItem(
                                it.getString("title"), it.num("max"), it.num("weightage"), it.getString("status"),
                                it.num("scored"), it.num("weighted"), it.getString("remark"),
                            )
                        },
                    )
                }.orEmpty(),
                exams = o.optJSONArray("exams")?.objects()?.map {
                    Exam(
                        it.getString("classId"), it.getString("code"), it.getString("name"), it.getString("type"),
                        it.getString("date"), it.getString("session"), it.getString("time"), it.getString("venue"), it.getString("seat"),
                    )
                }.orEmpty(),
                history = o.optJSONObject("history")?.let { h ->
                    h.keys().asSequence().associateWith { k ->
                        h.getJSONArray(k).objects().map {
                            AttendanceDay(
                                it.getString("date"), it.getString("slot"),
                                runCatching { AttendanceDay.Status.valueOf(it.getString("status")) }.getOrDefault(AttendanceDay.Status.Other),
                            )
                        }
                    }
                }.orEmpty(),
                profile = o.optJSONObject("profile")?.let { p ->
                    Profile(
                        p.getString("name"), p.getString("gender"), if (p.isNull("photo")) null else p.getString("photo"),
                        p.optJSONObject("hostel")?.let { HostelInfo(it.getString("block"), it.getString("room"), it.getString("bed"), it.getString("mess")) },
                    )
                },
                profileAt = o.optLong("profileAt"),
                hostel = o.optJSONObject("hostel")?.let { h ->
                    fun slips(k: String) = h.getJSONArray(k).objects().map {
                        NightSlip(it.getString("venue"), it.getString("purpose"), it.getString("to"), it.getString("from"),
                            it.getString("until"), it.getString("time"), it.getString("status"))
                    }
                    fun leaves(k: String) = h.getJSONArray(k).objects().map {
                        Leave(it.getString("place"), it.getString("reason"), it.getString("type"), it.getString("from"), it.getString("to"), it.getString("status"))
                    }
                    HostelRequests(slips("activeSlips"), slips("pastSlips"), leaves("activeLeave"), leaves("pastLeave"))
                },
                hostelAt = o.optLong("hostelAt"),
                calendar = o.optJSONObject("calendar")?.let { runCatching { AcademicCalendar.fromJson(it) }.getOrNull() },
                calendarAt = o.optLong("calendarAt"),
                faculty = o.optJSONArray("faculty")?.objects()?.map {
                    FacultyInfo(it.getString("id"), it.getString("name"), it.getString("designation"), it.getString("department"),
                        it.getString("school"), it.getString("email"), it.getString("cabin"))
                }.orEmpty(),
                facultyAt = o.optLong("facultyAt"),
                assignments = o.optJSONArray("assignments")?.objects()?.map { c ->
                    CourseAssignments(
                        c.getString("classId"), c.getString("code"), c.getString("name"), c.getString("type"),
                        c.getJSONArray("items").objects().map {
                            Assignment(
                                it.getString("title"), it.num("max"), it.num("weightage"), it.getString("due"),
                                if (it.isNull("updated")) null else it.getString("updated"),
                            )
                        },
                    )
                }.orEmpty(),
                grades = o.optJSONObject("grades")?.let { g ->
                    GradeHistory(
                        g.num("cgpa"), g.num("earned"),
                        g.getJSONArray("entries").objects().map {
                            GradeEntry(
                                it.getString("code"), it.getString("title"), it.getDouble("credits"),
                                it.getString("grade").ifEmpty { null }, it.getString("month"),
                            )
                        },
                    )
                },
            )
        }.getOrNull()

        private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
        private fun JSONObject.putNum(key: String, v: Double?): JSONObject = put(key, v ?: JSONObject.NULL)
        private fun JSONObject.num(key: String): Double? = if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

        /** "+ Mark: Applied Chemistry · Quiz 1 · 8.5/10" for each newly scored item. Not on the first marks fetch. */
        fun marksDiff(old: List<CourseMarks>?, new: List<CourseMarks>): List<String> {
            if (old.isNullOrEmpty()) return emptyList()
            val seen = old.flatMap { c -> c.items.filter { it.scored != null }.map { c.classId + "|" + it.title } }.toSet()
            return new.flatMap { c ->
                c.items.filter { it.scored != null && (c.classId + "|" + it.title) !in seen }.map {
                    "+ Mark: ${c.courseName} · ${it.title} · ${num(it.scored!!)}/${it.max?.let(::num) ?: "?"}"
                }
            }
        }

        /** "+ Physics: present · 38/51" for each course whose count moved. */
        fun diff(old: Snapshot?, new: List<AttendanceRow>): List<String> {
            if (old == null) return emptyList()
            val before = old.attendance.associateBy { it.courseCode + "|" + it.classType }
            return new.mapNotNull { row ->
                val prev = before[row.courseCode + "|" + row.classType] ?: return@mapNotNull null
                if (prev.attended == row.attended && prev.total == row.total) return@mapNotNull null
                val missed = (row.total - prev.total) - (row.attended - prev.attended)
                val what = when {
                    row.total > prev.total && missed == 0 -> "present"
                    row.total > prev.total && row.attended == prev.attended -> "absent"
                    else -> "updated"
                }
                val sign = if (what == "absent") "−" else "+"
                "$sign ${row.courseName}: $what · ${row.attended}/${row.total}"
            }
        }
    }
}
