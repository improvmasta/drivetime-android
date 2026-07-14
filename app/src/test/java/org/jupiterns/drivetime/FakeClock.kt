package org.jupiterns.drivetime

/**
 * A clock the test owns, for the elapsed-time logic in the silent-stop spine ([Health],
 * [Watchdog], [DriveSession]). Every rule they encode is measured in minutes or hours, so
 * without this the only honest way to test them would be to wait.
 *
 * [boot] defaults to a phone that has been up for a year: far longer than any gap a test builds,
 * so nothing reads as a reboot unless the test explicitly says so. [Health.classify] checks the
 * reboot case *first*, so a boot time left at 0 would silently turn every test in the file into a
 * test of the reboot path — passing, and proving nothing.
 */
class FakeClock(
    var wall: Long = 1_700_000_000_000L,
    var boot: Long = 365L * 24 * 60 * 60 * 1000L,
) : Clock.Source {

    override fun now(): Long = wall
    override fun sinceBoot(): Long = boot

    /** Move both clocks forward together — ordinary time passing, no reboot. */
    fun advance(ms: Long) { wall += ms; boot += ms }

    /** Time passed AND the phone rebooted in it: the wall clock moves on, but time-since-boot
     *  restarts from zero and has only been counting since the machine came back. */
    fun advanceAcrossReboot(ms: Long, upSince: Long = 60_000L) { wall += ms; boot = upSince }
}
