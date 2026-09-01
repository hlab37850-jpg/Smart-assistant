package com.smartassistant.app.importer.extractors

import android.content.Context
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.models.ImportKind
import java.io.File

/** جسر: قراءة PDF عبر PdfParserHelper ثم تحليل عبر ReportTextParser */
object PdfSmartImporter {

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long, context: Context? = null): ImportEngine.AnalyzeResult {
        val helper = PdfParserHelper(context)
        val report = helper.importFile(file)
        if (!report.success) throw IllegalStateException(report.error ?: "فشل قراءة ملف PDF")
        return ReportTextParser.parse(report.text, kind, session)
    }
}

/** حذف بيانات جلسة سابقة + تنظيف البيانات غير المرتبطة */
suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    runCatching { repo.db.customerDao().deleteUnlinked() }
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
