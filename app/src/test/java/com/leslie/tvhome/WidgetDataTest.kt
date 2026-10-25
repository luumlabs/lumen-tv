package com.luumlabs.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class WidgetDataTest {
    private fun title(imdb: Double? = null, votes: Int? = null, tmdb: Double? = null, tomato: Int? = null) = Charts.Title(
        id = "t", show = false, name = "T", year = 2026, runtime = 0, age = "", description = "", genres = emptyList(),
        poster = null, backdrop = null, imdb = imdb, imdbVotes = votes, tmdb = tmdb, jw = null, tomato = tomato,
        fresh = false, cast = emptyList(), director = null, provider = null, providerKind = null,
    )

    @Test fun criticScoreAveragesTheSitesOnOneScale() {
        assertEquals(85.0, title(imdb = 8.0, votes = 50_000, tmdb = 8.0, tomato = 95).criticScore!!, 1e-9)
        assertEquals(72.0, title(imdb = 7.2, votes = 9_000).criticScore!!, 1e-9)
    }

    @Test fun criticScoreIgnoresTitlesWithTooFewVotesOrNoScores() {
        assertNull(title(imdb = 9.6, votes = 200).criticScore)
        assertNull(title().criticScore)
        // A Tomatometer counts even when IMDb has few votes yet.
        assertEquals(90.0, title(votes = 200, tomato = 90).criticScore!!, 1e-9)
    }

    @Test fun runtimeReadsLikeTheAppleTvApp() {
        assertEquals("2h 14m", Charts.runtime(134))
        assertEquals("2h", Charts.runtime(120))
        assertEquals("52m", Charts.runtime(52))
        assertEquals("", Charts.runtime(0))
    }

    @Test fun weatherCodesInWords() {
        assertEquals("Clear", WeatherData.describe(0))
        assertEquals("Partly Cloudy", WeatherData.describe(2))
        assertEquals("Heavy Rain", WeatherData.describe(65))
        assertEquals("Thunderstorms", WeatherData.describe(95))
    }

    @Test fun ipPlacesAreCheckedAgainstTheTvClock() {
        val madrid = TimeZone.getTimeZone("Europe/Madrid")
        assertEquals(true, Geo.sameClock("Europe/Paris", madrid))
        // A VPN exit or a provider's hub in another country.
        assertEquals(false, Geo.sameClock("America/Caracas", madrid))
        assertEquals(false, Geo.sameClock("America/New_York", TimeZone.getTimeZone("America/Bogota")))
        // A neighbour's zone on the same time all year is no reason for doubt.
        assertEquals(true, Geo.sameClock("America/Lima", TimeZone.getTimeZone("America/Bogota")))
        // Unknown: no zone given, an unknown one, or the TV on plain UTC.
        assertNull(Geo.sameClock("", madrid))
        assertNull(Geo.sameClock("Mars/Olympus", madrid))
        assertNull(Geo.sameClock("Europe/Paris", TimeZone.getTimeZone("UTC")))
    }
}
