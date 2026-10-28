"""
Minimal server-side VTOP client: logs in (auto-solving the captcha) and scrapes
the attendance table, over plain HTTP with `requests` (no browser or WebView),
which keeps it small and fast.

Nothing here is stored or sent anywhere except vtop.vit.ac.in. Credentials are
passed in per call and never written to disk.
"""
import datetime as _dt
import time

import requests
from bs4 import BeautifulSoup

import captcha_solver

BASE = "https://vtop.vit.ac.in/vtop"
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")


class VtopError(Exception):
    """Generic VTOP failure (network, unexpected page, etc.)."""


class AuthError(VtopError):
    """Username/password rejected by VTOP."""


class CaptchaError(VtopError):
    """Captcha could not be solved within the retry budget."""


class RecaptchaError(VtopError):
    """VTOP is serving Google reCAPTCHA; auto-login can't proceed."""


def _utc() -> str:
    return _dt.datetime.now(_dt.timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT")


class VtopClient:
    def __init__(self, timeout: int = 25):
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": _UA})
        self.timeout = timeout
        self.csrf = None
        self.authorized_id = None

    # -- low level ----------------------------------------------------------
    def _get(self, path, **kw):
        return self.s.get(BASE + path, timeout=self.timeout, **kw)

    def _post(self, path, data, **kw):
        headers = {"Referer": BASE + "/login", "X-Requested-With": "XMLHttpRequest"}
        headers.update(kw.pop("headers", {}))
        return self.s.post(BASE + path, data=data, headers=headers, timeout=self.timeout, **kw)

    def _fresh_login_page(self) -> BeautifulSoup:
        """Walk open/page -> prelogin/setup to get a fresh login form + captcha."""
        r = self._get("/open/page")
        csrf = BeautifulSoup(r.text, "lxml").select_one("#stdForm input[name=_csrf]")
        if not csrf:
            raise VtopError("Could not read initial CSRF token from open/page.")
        r2 = self._post("/prelogin/setup", {"_csrf": csrf["value"], "flag": "VTOP"},
                        headers={"Referer": BASE + "/open/page"})
        return BeautifulSoup(r2.text, "lxml")

    # -- public -------------------------------------------------------------
    def login(self, username: str, password: str, max_attempts: int = 12):
        """Log in, auto-solving the text captcha. Returns on success; raises
        AuthError / CaptchaError / RecaptchaError / VtopError otherwise."""
        username = username.strip()
        last_solved = None
        dodges = 0
        for _ in range(max_attempts):
            soup = self._fresh_login_page()

            # The page can carry a reCAPTCHA widget next to the image
            # captcha, so it only counts when the image captcha is missing. Then walk
            # in again after a growing pause, hoping VTOP serves the image one.
            cap_img = (soup.select_one('#captchaBlock img[src^="data:image"]')
                       or soup.select_one('img[src^="data:image"]'))
            if not cap_img and soup.select_one("#recaptcha.g-recaptcha, div.g-recaptcha"):
                dodges += 1
                if dodges > 5:
                    raise RecaptchaError(
                        "VTOP kept showing Google reCAPTCHA instead of the text captcha. "
                        "Auto-login can't solve that; try again in a bit.")
                time.sleep(1.0 + dodges * 0.5)
                continue

            form = soup.select_one("#vtopLoginForm")
            csrf_el = soup.select_one("#vtopLoginForm input[name=_csrf]")
            if not (form and cap_img and csrf_el):
                raise VtopError("Login page layout not recognised (VTOP may have changed).")

            last_solved = captcha_solver.solve_data_uri(cap_img["src"])
            if len(last_solved) != 6 or "?" in last_solved:
                continue  # bad solve, get a fresh captcha

            r = self._post("/login", {
                "_csrf": csrf_el["value"],
                "username": username,
                "password": password,
                "captchaStr": last_solved,
            })
            text = BeautifulSoup(r.text, "lxml").get_text(" ", strip=True).lower()

            # Success: landed on the content page / authorizedIDX is present.
            if "/vtop/content" in r.url or "authorizedidx" in r.text.lower():
                self._capture_session(r.text)
                return

            if "invalid captcha" in text or "captcha" in text and "invalid" in text:
                continue  # wrong captcha -> retry with a new one

            if ("invalid" in text and ("login" in text or "credential" in text or "user" in text)) \
                    or "bad credentials" in text:
                raise AuthError("VTOP rejected your username or password.")

            # Unknown response: try to capture a session anyway, else retry.
            if "authorizedidx" in r.text.lower():
                self._capture_session(r.text)
                return
            # otherwise loop and try again

        raise CaptchaError(
            f"Could not solve the captcha after {max_attempts} attempts "
            f"(last guess: {last_solved!r}). Please try again.")

    def _capture_session(self, html: str):
        soup = BeautifulSoup(html, "lxml")
        aid = soup.select_one("#authorizedIDX") or soup.select_one("#authorizedID")
        csrf = (soup.select_one('input[name=_csrf]') or soup.select_one('meta[name=_csrf]'))
        self.authorized_id = (aid.get("value") if aid else None)
        if csrf:
            self.csrf = csrf.get("value") or csrf.get("content")
        if not self.authorized_id or not self.csrf:
            raise VtopError("Logged in but could not read the session token / ID.")

    def _base_params(self, **extra):
        p = {"_csrf": self.csrf, "authorizedID": self.authorized_id, "x": _utc()}
        p.update(extra)
        return p

    def list_semesters(self):
        """Hit the attendance landing page and return its semester dropdown as
        a list of {id, name}. Also refreshes self.csrf if the page carries one."""
        r = self._post("/academics/common/StudentAttendance",
                       self._base_params(verifyMenu="true"))
        soup = BeautifulSoup(r.text, "lxml")
        csrf = soup.select_one("input[name=_csrf]")
        if csrf and csrf.get("value"):
            self.csrf = csrf["value"]
        sel = soup.select_one("#semesterSubId, select[name=semesterSubId]")
        sems = []
        if sel:
            for opt in sel.find_all("option"):
                val = (opt.get("value") or "").strip()
                if val:
                    sems.append({"id": val, "name": opt.get_text(strip=True)})
        return sems

    def get_attendance(self, semester_sub_id: str = None):
        """Return {'semester': {...}, 'summary': [ ... ]}. If semester_sub_id is
        None, picks the first (most recent) semester VTOP offers."""
        sems = self.list_semesters()
        chosen = semester_sub_id
        chosen_name = None
        if sems:
            match = next((s for s in sems if s["id"] == semester_sub_id), None)
            if match is None:
                match = sems[0]
            chosen = match["id"]
            chosen_name = match["name"]
        if not chosen:
            raise VtopError("No semester found to query attendance for.")

        r = self._post("/processViewStudentAttendance",
                       self._base_params(semesterSubId=chosen))
        summary = self._parse_attendance(r.text)
        if not summary:
            # Fallback path the app uses when the direct call comes back empty.
            r = self._post("/academics/common/StudentAttendance",
                           self._base_params(semesterSubId=chosen, verifyMenu="true"))
            summary = self._parse_attendance(r.text)
        return {"semester": {"id": chosen, "name": chosen_name}, "summary": summary,
                "semesters": sems}

    @staticmethod
    def _parse_attendance(html: str):
        soup = BeautifulSoup(html, "lxml")
        table = soup.select_one("#AttendanceDetailDataTable")
        if not table:
            return []
        rows = []
        for tr in table.select("tbody tr"):
            if tr.select_one("td.dataTables_empty"):
                continue
            cells = tr.find_all("td")
            if len(cells) < 8:
                continue

            def ct(el):
                return " ".join(el.get_text(" ", strip=True).split())

            detail = ct(cells[2])
            parts = detail.split(" - ")
            course_code = parts[0] if parts else ""
            course_name = parts[1] if len(parts) > 1 else detail
            class_type = " - ".join(parts[2:]) if len(parts) > 2 else ""
            attended = int(ct(cells[5])) if ct(cells[5]).isdigit() else 0
            total = int(ct(cells[6])) if ct(cells[6]).isdigit() else 0
            pct_span = cells[7].select_one("span[style]")
            percentage = pct_span.get_text(strip=True) if pct_span else ct(cells[7])
            rows.append({
                "courseCode": course_code,
                "courseName": course_name,
                "classType": class_type,
                "attended": attended,
                "total": total,
                "percentage": percentage,
            })
        return rows

    def logout(self):
        if self.csrf and self.authorized_id:
            try:
                self._post("/logout", self._base_params())
            except Exception:
                pass
