package dev.medriid.davit.data

import dev.medriid.davit.vtop.SessionExpired
import dev.medriid.davit.vtop.UploadOutcome
import dev.medriid.davit.vtop.UserConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A file picked for an assignment, read into memory (VTOP takes 4 MB at most). */
class PickedFile(val name: String, val mime: String, val bytes: ByteArray) {
    val size: Int get() = bytes.size
}

/** Where one assignment's upload is. Kept outside the screen, so leaving it doesn't lose an upload or its OTP step. */
sealed interface UploadState {
    /** Picked and checked; waiting for the confirm tap. */
    data class Ready(val file: PickedFile) : UploadState

    data class Sending(val stage: String, val fraction: Float?) : UploadState

    /** VTOP is holding the new file until the code emailed to [email] goes in. */
    data class Otp(val file: String, val email: String, val error: String?, val pending: UploadOutcome.NeedsOtp) : UploadState

    data class Done(val updated: String?, val replaced: Boolean) : UploadState

    data class Failed(val message: String) : UploadState
}

/**
 * The assignment upload, step by step: pick (checked here, before anything is sent),
 * confirm, send with progress; a replacement then asks for the emailed OTP, with Cancel.
 * Every VTOP write here starts from a confirm button's [UserConsent], never from sync.
 */
class Uploads(private val repo: Repo) {
    private val _state = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val state: StateFlow<Map<String, UploadState>> = _state

    private fun set(key: String, s: UploadState?) = _state.update { if (s == null) it - key else it + (key to s) }

    /** Checks the file the way VTOP's page does (type and size), so a wrong one never leaves the phone. */
    /** [size] is what the file says it is (null if unknown); [bytes] null when it wasn't read (too big, or unreadable). */
    fun pick(key: String, name: String, mime: String, size: Long?, bytes: ByteArray?) {
        val problem = problemWith(name, size ?: bytes?.size?.toLong(), bytes)
        val ext = name.substringAfterLast('.', "").lowercase()
        set(
            key,
            if (problem != null || bytes == null) UploadState.Failed(problem ?: "Couldn't read that file.")
            else UploadState.Ready(PickedFile(name, mime.ifBlank { MimeByExt[ext] ?: "application/octet-stream" }, bytes)),
        )
    }

    fun clear(key: String) = set(key, null)

    /** The confirm tap: open VTOP's form for [code] and send the picked file through it. */
    fun send(key: String, consent: UserConsent, semesterId: String?, classId: String, code: String, replacing: Boolean) {
        val ready = _state.value[key] as? UploadState.Ready ?: return
        set(key, UploadState.Sending("Opening VTOP's upload form", null))
        repo.scope.launch {
            val outcome = try {
                repo.onVtop { c ->
                    val form = c.openUpload(consent, semesterId, classId, code)
                    set(key, UploadState.Sending("Uploading ${ready.file.name}", 0f))
                    c.upload(consent, form, ready.file.name, ready.file.mime, ready.file.bytes) { sent, total ->
                        set(key, UploadState.Sending("Uploading ${ready.file.name}", if (total > 0) sent.toFloat() / total else null))
                    }
                }
            } catch (e: Exception) {
                set(key, UploadState.Failed(repo.explain(e)))
                return@launch
            }
            land(key, classId, ready.file.name, outcome, replacing)
        }
    }

    /** The emailed code. Not retried after a re-login: VTOP holds the new file in the old session. */
    fun submitOtp(key: String, consent: UserConsent, classId: String, code: String, otp: String) {
        val wait = _state.value[key] as? UploadState.Otp ?: return
        set(key, UploadState.Sending("Checking the code", null))
        repo.scope.launch {
            val outcome = try {
                repo.onVtop(retry = false) { c -> c.submitUploadOtp(consent, wait.pending, otp, code) }
            } catch (e: SessionExpired) {
                set(key, UploadState.Failed("VTOP signed you out before the code went in, so nothing changed. Your old file is still there; upload again."))
                return@launch
            } catch (e: Exception) {
                // Back to the code field: the file may still be waiting on VTOP.
                set(key, wait.copy(error = repo.explain(e)))
                return@launch
            }
            land(key, classId, wait.file, outcome, replacing = true)
        }
    }

    /** Cancel on the OTP step: VTOP drops the new file and keeps the old one, as its own Cancel does. */
    fun cancel(key: String, classId: String) {
        val was = _state.value[key]
        set(key, null)
        if (was is UploadState.Otp) repo.scope.launch {
            runCatching {
                val items = repo.onVtop(retry = false) { c ->
                    c.cancelUpload(classId)
                    c.courseAssignments(classId)
                }
                repo.updateAssignments(classId, items)
            }
        }
    }

    private suspend fun land(key: String, classId: String, file: String, outcome: UploadOutcome, replacing: Boolean) {
        when (outcome) {
            is UploadOutcome.Uploaded -> {
                set(key, UploadState.Done(outcome.updated, replacing))
                runCatching { repo.updateAssignments(classId, repo.onVtop { c -> c.courseAssignments(classId) }) }
            }
            is UploadOutcome.NeedsOtp -> set(key, UploadState.Otp(file, outcome.email, outcome.error, outcome))
            is UploadOutcome.Refused -> set(key, UploadState.Failed(outcome.message))
        }
    }

    companion object {
        /** VTOP's own limit: 4194304 bytes. */
        const val MAX = 4 * 1024 * 1024

        /** What's wrong with [name] for VTOP (its type, or its size), or null if it can go. Same rules as VTOP's page. */
        fun problemWith(name: String, size: Long?, bytes: ByteArray?): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            val length = size ?: bytes?.size?.toLong()
            return when {
                ext !in Allowed -> "VTOP takes PDF, Word (doc, docx) or Excel (xls, xlsx) files. \"$name\" isn't one."
                length != null && length > MAX -> "\"$name\" is ${sizeText(length)}. VTOP takes 4 MB at most."
                (bytes?.size ?: 0) > MAX -> "\"$name\" is over 4 MB. VTOP takes 4 MB at most."
                bytes == null -> "Couldn't read that file."
                bytes.isEmpty() -> "\"$name\" is empty."
                else -> null
            }
        }
        val Allowed = setOf("pdf", "xls", "xlsx", "doc", "docx")
        private val MimeByExt = mapOf(
            "pdf" to "application/pdf",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
        /** For the system file picker. */
        val PickerTypes = MimeByExt.values.toTypedArray()
    }
}
