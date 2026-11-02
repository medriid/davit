package dev.medriid.davit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteTest {
    @Test
    fun aPdfRouteSurvivesItsId() {
        val route = Route.PdfView("cert:/events/x|2026|ABC", "/data/user/0/app/cache/previews/a b/Notes (1).pdf", "Module 3 | Trees & graphs")
        assertEquals(route, Route.parse(route.id))
    }

    @Test
    fun newPlainRoutesParse() {
        for (r in listOf(Route.Circulars, Route.Windows, Route.Projects, Route.Rules, Route.OtherExams, Route.Certificates, Route.Sports, Route.ClubDirectory, Route.SignIns)) {
            assertEquals(r, Route.parse(r.id))
        }
    }
}
