# 04 · VTOP API reference

All endpoints are under `https://vtop.vit.ac.in/vtop`. Everything is
`POST` `application/x-www-form-urlencoded` with header
`X-Requested-With: XMLHttpRequest`, **except** the initial `GET /open/page`.

Every authenticated POST carries these base params:
```
_csrf         = <token read from the content page>
authorizedID  = <your reg number, from #authorizedIDX>
x             = <current UTC date, e.g. "Tue, 28 Oct 2026 13:00:00 GMT">
```

## Login flow
| Step | Request | Params | Result |
|------|---------|--------|--------|
| 1 | `GET /open/page` | — | sets `JSESSIONID`,`SERVERID`; page has `#stdForm` with `_csrf` |
| 2 | `POST /prelogin/setup` | `_csrf`, `flag=VTOP` | returns the student login page (`#vtopLoginForm`) |
| 3 | `POST /login` | `_csrf`, `username`, `password`, `captchaStr` | success → `/vtop/content`; failure → `/vtop/login/error` |

- The login page's captcha is an inline `data:image/jpeg;base64,…` inside
  `#vtopLoginForm` (served natively at 200×40).
- A fresh captcha (without reloading the whole page) is at
  `GET /get/new/captcha`.
- Failure disambiguation (verified): a wrong captcha → page text contains
  "invalid captcha"; a right captcha but wrong credentials → "invalid
  credentials" / "invalid LoginId". daVIT uses exactly this to retry captcha vs.
  fail auth.
- After success, read `authorizedID` from `#authorizedIDX` and a fresh `_csrf`
  from `input[name=_csrf]` on the content page.

## Scraping endpoints
> Many VTOP endpoints need a "warm-up" POST to the menu landing page first
> (`verifyMenu=true`), otherwise the sub-action returns an empty table.

### Attendance
1. Warm-up: `POST /academics/common/StudentAttendance` + `verifyMenu=true`
2. Summary: `POST /processViewStudentAttendance` + `semesterSubId`
   → table `#AttendanceDetailDataTable`, cols: `[_, _, "code - name - type", _, _, attended, total, %]`
3. Per-course detail: `POST /processViewAttendanceDetail` +
   `semesterSubId`, `registerNumber`, `courseId`, `courseType`
   (ids come from the summary row's `callStudentAttendanceDetailDisplay('semId','regNo','courseId','courseType')` onclick)
   → table `#StudentAttendanceDetailDataTable`, cols: `[_, date, slot, _, status]`

### Timetable
`POST /processViewTimeTable` + `semesterSubId` → the table containing
"Class Group" + "Course Option". Only rows whose status is "registered" **and**
"approved" are kept. Class id is a `VL\d+` token (≥8 chars) found in the row.

### Marks
`POST /examinations/doStudentMarkView` + `semesterSubId`
→ `#fixedTableContainer table.customTable`, rows come in pairs (subject row +
marks sub-table `table.customTable-level1`).

### Assignments (Digital Assignments)
1. List: `POST /examinations/doDigitalAssignment` + `semesterSubId`
   → table with "Class Nbr"+"Course Code"+"Dashboard"
2. Per-course: `POST /examinations/processDigitalAssignment` + `classId`
   → table with "Weightage"+"Due Date"+"Last Updated On"

### Exam schedule
`POST /examinations/doSearchExamScheduleForStudent` + `semesterSubId`
→ `#fixedTableContainer`; rows grouped by an exam-type header row
(`td.panelHead-secondary[colspan]`).

### Profile (+ hostel info)
`POST /studentsRecord/StudentProfileAllView` + `verifyMenu=true`
→ label/value `<td>` pairs (STUDENT NAME, GENDER). Photo is an inline
`img.img.border.border-primary[src^="data:image/"]`. Hostel info is in a
"HOSTEL INFORMATION" accordion.

### Grade history
`POST /examinations/examGradeView/StudentGradeHistory` + `verifyMenu=true`
→ `table.customTable` containing "Effective Grades" (history) and "Curriculum
Details" (credit progress).

### Faculty details
`POST /hrms/EmployeeSearch1ForStudent` + `empId`
→ label/value rows (NAME OF THE FACULTY, DESIGNATION, NAME OF DEPARTMENT,
SCHOOL / CENTRE NAME, E-MAIL ID, CABIN NUMBER). empIds come from matching your
timetable faculty names against the faculty directory JSON.

### Night slip (hostellers)
Warm-up `/hostels/late/hour/student/request/1` (`verifyMenu=true`), then
`/hostels/late/hour/student/request/9` (`control=status`) and `/request/11`
(`control=history`) → table `#LateHourStatusTable`.

### Hostel leave (hostellers)
Warm-up `/hostels/student/leave/1` (`verifyMenu=true`), then `/leave/4`
(`control=status`) and `/leave/6` (`control=history`) → tables
`#LeaveAppliedTable` / `#LeaveHistoryTable`.

### Logout
`POST /logout` + base params.

## Semester ids
`semesterSubId` identifies a term, e.g. **`VL20262701`** (Fall 2026). The
attendance landing page carries a `select#semesterSubId` dropdown of all
available terms, newest first. A term can be listed before it starts, so daVIT
defaults to the newest one with attendance rows.

## Read-only stance
Never call write/upload endpoints. daVIT blocks
`/examinations/processDigitalAssignmentUpload` and
`/examinations/doDAssignmentUploadMethod` outright, in an OkHttp interceptor, so a
bug can't submit anything to VTOP.
