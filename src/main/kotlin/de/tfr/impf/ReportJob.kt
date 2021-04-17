package de.tfr.impf

import de.tfr.impf.config.Config
import de.tfr.impf.page.CookieNagComponent
import de.tfr.impf.page.LocationPage
import de.tfr.impf.page.MainPage
import de.tfr.impf.page.RequestCodePage
import de.tfr.impf.selenium.createDriver
import de.tfr.impf.slack.SlackClient
import mu.KotlinLogging
import org.openqa.selenium.WebDriver

val log = KotlinLogging.logger("ReportJob")

class ReportJob {

    private var driver: WebDriver = createDriver()

    private val locations = Config.locationList()
    private val personAge = Config.personAge
    private val sendRequest = Config.sendRequest
    private val mobileNumber = Config.mobileNumber
    private val email = Config.email

    fun reportFreeSlots() {
        log.info { "Person age: $personAge" }
        log.info { "Started checking these ${locations.size} locations:\n$locations" }
        while (true) {
            checkLocations()
        }
    }

    private fun checkLocations() {
        for (location in locations) {
            try {
                checkLocation(location)
            } catch (e: Exception) {
                log.error(e) { "Failed to check location: $location\n" + e.message }
            }
            Thread.sleep(1 * 60 * 1000)
        }
    }


    private fun checkLocation(location: Config.Location) {
        val mainPage = openMainPage(driver)
        val cookieNag = CookieNagComponent(driver)
        mainPage.isDisplayed()
        mainPage.chooseLocation(location.name)
        Thread.sleep(500)
        cookieNag.acceptCookies()
        mainPage.submitLocation()
        val locationPage = LocationPage(driver)
        if (locationPage.isDisplayed()) {
            log.debug { "Changed to location page: $location" }
            if (location.hasCode()) {
                searchFreeDateByCode(locationPage, location)
            } else {
                checkClaim(locationPage, cookieNag, location)
            }
        }
    }

    private fun searchFreeDateByCode(
        locationPage: LocationPage,
        location: Config.Location
    ) {
        locationPage.confirmClaim()
        val code = location.placementCode
        if (code != null) {
            locationPage.enterCodeSegment0(code)
            locationPage.searchForFreeDate()
            locationPage.searchForVaccinateDate()
            if (locationPage.hasNoVaccinateDateAvailable()) {
                log.debug { "Correct code, but not free vaccination slots: $location" }
            } else {
                sendMessageFoundDates(location)
                waitLongForUserInput()
            }
        }
    }

    private fun checkClaim(
        locationPage: LocationPage,
        cookieNag: CookieNagComponent,
        location: Config.Location
    ) {
        locationPage.askForClaim()
        Thread.sleep(2000)
        cookieNag.acceptCookies()
        if (locationPage.isFull()) {
            log.debug { "Location: $location is full" }
        } else {
            locationPage.checkCorrectPerson()
            locationPage.enterAge(personAge)
            locationPage.submitInput()
            Thread.sleep(2000)
            if (locationPage.isFull()) {
                log.debug { "Location: $location is full" }
            } else {
                sendMessageFoundFreeSeats(location)
                if (sendRequest) {
                    requestCode()
                }
                waitLongForUserInput()
            }
        }
    }

    private fun waitLongForUserInput() {
        val minutes = 20L
        Thread.sleep(minutes * 60 * 1000)
    }

    private fun requestCode() {
        val requestCodePage = RequestCodePage(driver)
        requestCodePage.fillEmail(email)
        requestCodePage.fillMobileNumber(mobileNumber)
        requestCodePage.requestCode()
    }


    private fun sendMessageFoundFreeSeats(location: Config.Location) {
        "Found free seats in location ${location.name}:${driver.currentUrl}"
    }

    private fun sendMessageFoundDates(location: Config.Location) {
        "Found free vaccination dates in location" +
                "Ten minutes left to choose a date"
        " ${location.name}:${driver.currentUrl}"
    }

    private fun sendMessage(message: String) {
        log.info { message }
        if (Config.isSlackEnabled()) {
            SlackClient().sendMessage(message)
        }
    }

    private fun openMainPage(driver: WebDriver): MainPage {
        val mainPage = MainPage(driver)
        mainPage.open()
        log.debug { "Choose State: " + mainPage.chooseState()?.text }
        mainPage.chooseState()?.click()
        mainPage.chooseStateItemBW()
        return mainPage
    }
}

