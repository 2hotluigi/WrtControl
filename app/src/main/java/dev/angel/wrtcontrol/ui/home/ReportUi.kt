package dev.angel.wrtcontrol.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.ui.design.OfferGroup
import java.io.File

/** Cómo va el informe del router: preparándose ([busy]) o con un error que contar. */
data class ReportUi(val busy: Boolean = false, val error: String? = null, val onShare: () -> Unit)

/** Qué es el informe del router, qué no lleva y el botón para compartirlo (ver openwrt/DeviceReport.kt). */
@Composable
fun ReportCard(report: ReportUi) {
    OfferGroup(
        stringResource(R.string.report_title),
        stringResource(R.string.report_body),
        action = stringResource(if (report.busy) R.string.report_busy else R.string.report_share),
        onAction = report.onShare,
        enabled = !report.busy,
        problem = report.error,
        actionTag = "report-share",
    )
}

/** Guarda [json] en la caché de la app y abre el menú de compartir de Android con ese archivo. */
fun shareReport(context: Context, json: String) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "wrtcontrol-router.json").apply { writeText(json) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_share_subject))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.report_title)))
}
