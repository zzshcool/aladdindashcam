package com.aladdin.dashcam.ui

import android.location.Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Administrative-area suggestions only; coordinates cannot identify national-road jurisdiction. */
object ReportJurisdiction {
    val regions = listOf("臺北市", "新北市", "桃園市", "臺中市", "臺南市", "高雄市", "基隆市", "新竹市", "嘉義市",
        "新竹縣", "苗栗縣", "彰化縣", "南投縣", "雲林縣", "嘉義縣", "屏東縣", "宜蘭縣", "花蓮縣", "臺東縣", "澎湖縣", "金門縣", "連江縣")
    const val DIRECTORY = "https://www.gov.tw/News_Content_26_802190"
    // Official agency directories checked 2026-10-28; unknown entries use the government directory.
    private val portals = mapOf(
        "臺北市" to "https://prsweb.tcpd.gov.tw/",
        "新北市" to "https://tvrs.ntpd.gov.tw/",
        "桃園市" to "https://tvrweb.typd.gov.tw/",
        "臺中市" to "https://suggest.police.taichung.gov.tw/",
        "臺南市" to "https://tr.tnpd.gov.tw/",
        "高雄市" to "https://policemail.kcg.gov.tw/",
        "基隆市" to "https://tptv.klg.gov.tw/",
        "新竹市" to "https://tra.hccp.gov.tw/",
        "新竹縣" to "https://traffic.hchpb.gov.tw/",
        "苗栗縣" to "https://trv.mpb.gov.tw/",
        "彰化縣" to "https://traffic.chpb.gov.tw/",
        "雲林縣" to "https://trv.ylhpb.gov.tw/",
        "嘉義市" to "https://trn.ccpb.gov.tw/",
        "嘉義縣" to "https://www.cypd.gov.tw/",
        "屏東縣" to "https://trafficmailbox.ptpolice.gov.tw/",
        "宜蘭縣" to "https://ppl.report.ilcpb.gov.tw/",
        "花蓮縣" to "https://hlpb.twgov.mobi/",
        "臺東縣" to "https://www.ttcpb.gov.tw/",
        "澎湖縣" to "https://www.phpb.gov.tw/",
        "金門縣" to "https://kpb.kinmen.gov.tw/"
    )
    fun portal(region: String): String = portals[region] ?: DIRECTORY
    fun region(addresses: List<Address>): String? = addresses.mapNotNull { address ->
        if (address.countryCode != "TW") null
        else listOfNotNull(address.adminArea, address.subAdminArea, address.locality).map { it.replace('台', '臺') }
            .firstOrNull { it in regions }
    }.distinct().singleOrNull()

    suspend fun resolve(context: android.content.Context, lat: Double, lon: Double): String? = withContext(Dispatchers.Default) {
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        val features = context.assets.open("report_counties.geojson").bufferedReader().use {
            org.json.JSONObject(it.readText()).getJSONArray("features")
        }
        fun inside(ring: org.json.JSONArray): Boolean {
            var result = false
            for (i in 0 until ring.length()) {
                val a = ring.getJSONArray(i); val b = ring.getJSONArray((i + 1) % ring.length())
                val ax = a.getDouble(0); val ay = a.getDouble(1)
                val bx = b.getDouble(0); val by = b.getDouble(1)
                if ((ay > lat) != (by > lat) && lon < (bx - ax) * (lat - ay) / (by - ay) + ax) result = !result
            }
            return result
        }
        fun polygon(rings: org.json.JSONArray): Boolean =
            (0 until rings.length()).count { inside(rings.getJSONArray(it)) } % 2 == 1
        val matches = mutableListOf<String>()
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i); val geometry = feature.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            val found = when (geometry.getString("type")) {
                "Polygon" -> polygon(coordinates)
                "MultiPolygon" -> (0 until coordinates.length()).count { polygon(coordinates.getJSONArray(it)) } % 2 == 1
                else -> false
            }
            if (found) matches.add(feature.getJSONObject("properties").getString("COUNTYNAME"))
        }
        matches.distinct().singleOrNull()?.takeIf { it in regions }
    }
}
