package mattecarra.accapp.utils

import android.content.Context
import android.os.Environment
import com.topjohnwu.superuser.Shell
import mattecarra.accapp.adapters.Schedule
import mattecarra.accapp.data.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception
import java.net.URL


object AccUtils {

    // String resources
    private val STRING_UNKNOWN = "Unknown"
    private val STRING_DISCHARGING = "Discharging"
    private val STRING_CHARGING = "Charging"

    /**
     * ACC Config Regex
     */
    val CAPACITY_CONFIG_REGEXP = """^\s*capacity=(\d*),(\d*),(\d+)-(\d+)""".toRegex(RegexOption.MULTILINE)
    val COOLDOWN_CONFIG_REGEXP = """^\s*coolDown=(\d*)/(\d*)""".toRegex(RegexOption.MULTILINE)
    val TEMP_CONFIG_REGEXP = """^\s*temp=(\d*)-(\d*)_(\d*)""".toRegex(RegexOption.MULTILINE)
    val RESET_UNPLUGGED_CONFIG_REGEXP = """^\s*resetUnplugged=(true|false)""".toRegex(RegexOption.MULTILINE)
    val ON_BOOT_EXIT = """^\s*onBootExit=(true|false)""".toRegex(RegexOption.MULTILINE)
    val ON_BOOT = """^\s*onBoot=([^#]+)""".toRegex(RegexOption.MULTILINE)
    val ON_PLUGGED = """^\s*onPlugged=([^#]+)""".toRegex(RegexOption.MULTILINE)
    val VOLT_FILE = """^\s*cVolt=([^:#\s]+):(\d+)""".toRegex(RegexOption.MULTILINE)
    val SWITCH = """^\s*switch=([^#]+)""".toRegex(RegexOption.MULTILINE)

    val defaultConfig: AccConfig = AccConfig(
        Capacity(5, 60, 70, 80),
        Cooldown(50, 10),
        Temp(40, 45, 90),
        VoltControl(null, null),
        false,
        false,
        null,
        null,
        null
    )

    fun readConfig(): AccConfig {
        val config = readConfigToStringArray().joinToString(separator = "\n")

        val (shutdown, coolDown, resume, pause) = CAPACITY_CONFIG_REGEXP.find(config)!!.destructured
        val capacity = Capacity(shutdown.toIntOrNull() ?: 0, coolDown.toInt() ?: 101, resume.toInt(), pause.toInt())

        val coolDownMatchResult = COOLDOWN_CONFIG_REGEXP.find(config)
        val cooldown: Cooldown? =
            coolDownMatchResult?.let {
                val (coolDownChargeSeconds, coolDownPauseSeconds) = coolDownMatchResult.destructured
                coolDownChargeSeconds.toIntOrNull()?.let { chargeInt ->
                    coolDownPauseSeconds.toIntOrNull()?.let { Cooldown(chargeInt, it) }
                }
            }

        val (coolDownTemp, pauseChargingTemp, waitSeconds) = TEMP_CONFIG_REGEXP.find(config)!!.destructured
        val temp = Temp(
            coolDownTemp.toIntOrNull()?.let { it/10 } ?: 90,
            pauseChargingTemp.toIntOrNull()?.let { it/10 } ?: 95,
            waitSeconds.toIntOrNull() ?: 90
        )

        val cVolt = VOLT_FILE.find(config)?.destructured
        val voltControl = VoltControl(cVolt?.component1(), cVolt?.component2()?.toIntOrNull())

        return AccConfig(
            capacity,
            cooldown,
            temp,
            voltControl,
            RESET_UNPLUGGED_CONFIG_REGEXP.find(config)?.destructured?.component1() == "true",
            ON_BOOT_EXIT.find(config)?.destructured?.component1() == "true",
            ON_BOOT.find(config)?.destructured?.component1()?.trim(),
            ON_PLUGGED.find(config)?.destructured?.component1()?.trim(),
            getCurrentChargingSwitch()
        )
    }

    @Throws(IOException::class)
    fun readConfigToStringArray(): List<String> {
        val config = File(Environment.getExternalStorageDirectory(), "acc/config.txt")
        return if (config.exists())
            config.readText(charset = Charsets.UTF_8).split("\n")
        else
            emptyList()
    }

    @Throws(IOException::class)
    fun writeConfigFromStringArray(text: List<String>): Boolean {
        val config = File(Environment.getExternalStorageDirectory(), "acc/config.txt")
        if (config.exists()) {
            config.writeText(text.joinToString(separator = "\n"))
            return true
        } else {
            return false
        }
    }

    //update temp command
    fun updateTempCommand(cooldDownTemp: Int, pauseChargingTemp: Int, waitSeconds: Int) = "acc -s temp ${cooldDownTemp*10}-${pauseChargingTemp*10}_$waitSeconds"

    fun updateTemp(cooldDownTemp: Int, pauseChargingTemp: Int, waitSeconds: Int): Boolean {
        return Shell.su(updateTempCommand(cooldDownTemp, pauseChargingTemp, waitSeconds)).exec().isSuccess
    }

    //Update cool down command
    fun updateCoolDownCommand(charge: Int, pause: Int) = "acc -s coolDown $charge/$pause"

    fun updateCoolDown(charge: Int, pause: Int): Boolean {
        return Shell.su(updateCoolDownCommand(charge, pause)).exec().isSuccess
    }

    //Update capacity command
    fun updateCapacityCommand(shutdown: Int, coolDown: Int, resume: Int, pause: Int): String = "acc -s capacity $shutdown,$coolDown,$resume-$pause"

    fun updateCapacity(shutdown: Int, coolDown: Int, resume: Int, pause: Int): Boolean {
        return Shell.su(updateCapacityCommand(shutdown, coolDown, resume, pause)).exec().isSuccess
    }

    //reset unplugged command
    fun updateResetUnpluggedCommand(resetUnplugged: Boolean): String = "acc -s resetUnplugged $resetUnplugged"

    fun updateResetUnplugged(resetUnplugged: Boolean): Boolean {
        return Shell.su(updateResetUnpluggedCommand(resetUnplugged)).exec().isSuccess
    }

    //update on boot exit
    fun updateOnBootExitCommand(value: Boolean): String = "acc -s onBootExit $value"

    fun updateOnBootExit(value: Boolean): Boolean {
        return Shell.su(updateOnBootExitCommand(value)).exec().isSuccess
    }

    //Update on boot
    fun updateOnBootCommand(value: String?): String = "acc -s onBoot${value?.let{ " $it" } ?: ""}"

    fun updateOnBoot(value: String?): Boolean {
        return Shell.su(updateOnBootCommand(value)).exec().isSuccess
    }

    //Update on plugged
    fun updateOnPluggedCommand(value: String?): String = "acc -s onPlugged${value?.let{ " $it" } ?: ""}"

    fun updateOnPlugged(value: String?): Boolean {
        return Shell.su(updateOnPluggedCommand(value)).exec().isSuccess
    }

    //Update volt file
    fun updateVoltageCommand(voltControl: String?, voltMax: Int?): String {
        return if(voltControl != null && voltMax != null)
            "acc --set cVolt $voltControl:$voltMax"
        else if(voltMax != null)
            "acc --set cVolt $voltMax"
        else
            "acc --set cVolt"
    }

    fun updateVoltage(voltControl: String?, voltMax: Int?): Boolean {
        return Shell.su(updateVoltageCommand(voltControl, voltMax)).exec().isSuccess
    }

    fun listVoltageSupportedControlFiles(): List<String> {
        val res = Shell.su("acc -v :").exec()
        return if(res.isSuccess) res.out.filter { it.isNotEmpty() } else emptyList()
    }

    fun resetBatteryStats(): Boolean {
        return Shell.su("acc -R").exec().isSuccess
    }

    /**
     * Regex for acc -i (info)
     */
    // Regex for determining NAME of BATTERY
    private val NAME_REGEXP = """^\s*NAME=([a-zA-Z0-9]+)""".toRegex(RegexOption.MULTILINE)
    // Regex for INPUT_SUSPEND
    private val INPUT_SUSPEND_REGEXP = """^\s*INPUT_SUSPEND=(0|1)""".toRegex(RegexOption.MULTILINE)
    private val STATUS_REGEXP = """^\s*STATUS=(Charging|Discharging|Not charging)""".toRegex(RegexOption.MULTILINE)
    private val HEALTH_REGEXP = """^\s*HEALTH=([a-zA-Z]+)""".toRegex(RegexOption.MULTILINE)
    // Regex for PRESENT value
    private val PRESENT_REGEXP = """^\s*PRESENT=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for determining CHARGE_TYPE
    private val CHARGE_TYPE_REGEXP = """^\s*CHARGE_TYPE=(N/A|[a-zA-Z]+)""".toRegex(RegexOption.MULTILINE)
    // Regex for battery CAPACITY
    private val CAPACTIY_REGEXP = """^\s*CAPACITY=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for CHARGER_TEMP
    private val CHARGER_TEMP_REGEXP = """^\s*CHARGER_TEMP=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for CHARGER_TEMP_MAX
    private val CHARGER_TEMP_MAX_REGEXP = """^\s*CHARGER_TEMP_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for INPUT_CURRENT_LIMITED, 0 = false, 1 = true
    private val INPUT_CURRENT_LIMITED_REGEXP = """^\s*INPUT_CURRENT_LIMITED=(0|1)""".toRegex(RegexOption.MULTILINE)
    private val VOLTAGE_NOW_REGEXP = """^\s*VOLTAGE_NOW=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for VOLTAGE_MAX
    private val VOLTAGE_MAX_REGEXP = """^\s*VOLTAGE_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for VOLTAGE_QNOVO
    private val VOLTAGE_QNOVO_REGEXP = """^\s*VOLTAGE_QNOVO=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CURRENT_NOW_REGEXP = """^\s*CURRENT_NOW=(-?\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for CURRENT_QNOVO
    private val CURRENT_QNOVO_REGEXP = """^\s*CURRENT_NOW=(-?\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for CONSTANT_CHARGE_CURRENT_MAX
    private val CONSTANT_CHARGE_CURRENT_MAX_REGEXP = """^\s*CONSTANT_CHARGE_CURRENT_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val TEMP_REGEXP = """^\s*TEMP=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for remaining 'acc -i' values
    private val TECHNOLOGY_REGEXP = """^\s*TECHNOLOGY=([a-zA-Z\-]+)""".toRegex(RegexOption.MULTILINE)
    private val STEP_CHARGING_ENABLED_REGEXP = """^\s*STEP_CHARGING_ENABLED=(0|1)""".toRegex(RegexOption.MULTILINE)
    private val SW_JEITA_ENABLED_REGEXP = """^\s*SW_JEITA_ENABLED=(0|1)""".toRegex(RegexOption.MULTILINE)
    private val TAPER_CONTROL_ENABLED_REGEXP = """^\s*TAPER_CONTROL_ENABLED=(0|1)""".toRegex(RegexOption.MULTILINE)
    // CHARGE_DISABLE is true when ACC disables charging due to conditions
    private val CHARGE_DISABLE_REGEXP = """^\s*TAPER_CONTROL_ENABLED=(0|1)""".toRegex(RegexOption.MULTILINE)
    // CHARGE_DONE is true when the battery is done charging.
    private val CHARGE_DONE_REGEXP = """^\s*CHARGE_DONE=(0|1)""".toRegex(RegexOption.MULTILINE)
    private val PARALLEL_DISABLE_REGEXP = """^\s*PARALLEL_DISABLE=(0|1)""".toRegex(RegexOption.MULTILINE)
    private val SET_SHIP_MODE_REGEXP = """^\s*SET_SHIP_MODE=(0|1)""".toRegex(RegexOption.MULTILINE)
    private val DIE_HEALTH_REGEXP = """^\s*DIE_HEALTH=([a-zA-Z]+)""".toRegex(RegexOption.MULTILINE)
    private val RERUN_AICL_REGEXP = """^\s*RERUN_AICL=(0|1)""".toRegex(RegexOption.MULTILINE)
    private val DP_DM_REGEXP = """^\s*DP_DM=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CHARGE_CONTROL_LIMIT_MAX_REGEXP = """^\s*CHARGE_CONTROL_LIMIT_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CHARGE_CONTROL_LIMIT_REGEXP = """^\s*CHARGE_CONTROL_LIMIT=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CHARGE_COUNTER_REGEXP = """^\s*CHARGE_COUNTER=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val INPUT_CURRENT_MAX_REGEXP = """^\s*INPUT_CURRENT_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CYCLE_COUNT_REGEXP = """^\s*CYCLE_COUNT=(\d+)""".toRegex(RegexOption.MULTILINE)

    fun getBatteryInfo(): BatteryInfo {
        val info =  Shell.su("acc -i").exec().out.joinToString(separator = "\n")

        return BatteryInfo(
            NAME_REGEXP.find(info)?.destructured?.component1() ?: STRING_UNKNOWN,
            INPUT_SUSPEND_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let { // If r == true (input is suspended)
                it == 0
            },
            STATUS_REGEXP.find(info)?.destructured?.component1() ?: STRING_DISCHARGING,
            HEALTH_REGEXP.find(info)?.destructured?.component1() ?: STRING_UNKNOWN,
            PRESENT_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CHARGE_TYPE_REGEXP.find(info)?.destructured?.component1() ?: STRING_UNKNOWN,
            CAPACTIY_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CHARGER_TEMP_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull()?.let { it/10 } ?: -1,
            CHARGER_TEMP_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull()?.let { it/10 } ?: -1,
            INPUT_CURRENT_LIMITED_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            VOLTAGE_NOW_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            VOLTAGE_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            VOLTAGE_QNOVO_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CURRENT_NOW_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CURRENT_QNOVO_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CONSTANT_CHARGE_CURRENT_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            TEMP_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull()?.let { it/10 } ?: -1,
            TECHNOLOGY_REGEXP.find(info)?.destructured?.component1() ?: STRING_UNKNOWN,
            STEP_CHARGING_ENABLED_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            SW_JEITA_ENABLED_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            TAPER_CONTROL_ENABLED_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            CHARGE_DISABLE_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            CHARGE_DONE_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            PARALLEL_DISABLE_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            SET_SHIP_MODE_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            DIE_HEALTH_REGEXP.find(info)?.destructured?.component1() ?: STRING_UNKNOWN,
            RERUN_AICL_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            DP_DM_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull().let {
                it == 0
            },
            CHARGE_CONTROL_LIMIT_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CHARGE_CONTROL_LIMIT_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            INPUT_CURRENT_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CYCLE_COUNT_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1
        )
    }

    fun isBatteryCharging(): Boolean {
        return Shell.su("acc -i").exec().out.find { it.matches(STATUS_REGEXP) } == "STATUS=Charging"
    }

    fun isAccdRunning(): Boolean {
        return Shell.su("acc -D").exec().out.find { it.contains("accd is running") } != null
    }

    fun abcStartDaemon(): Boolean {
        return Shell.su("acc -D start").exec().isSuccess
    }

    fun abcRestartDaemon(): Boolean {
        return Shell.su("acc -D restart").exec().isSuccess
    }

    fun abcStopDaemon(): Boolean {
        return Shell.su("acc -D stop").exec().isSuccess
    }

    fun deleteSchedule(once: Boolean, name: String): Boolean {
        return Shell.su("djs cancel ${if(once) "once" else "daily" } $name").exec().isSuccess
    }

    fun schedule(once: Boolean, hour: Int, minute: Int, commands: List<String>): Boolean {
        return AccUtils.schedule(once, hour, minute, commands.joinToString(separator = "; "))
    }

    fun schedule(once: Boolean, hour: Int, minute: Int, commands: String): Boolean {
        return Shell.su("djs ${if(once) 'o' else 'd' } ${String.format("%02d", hour)} ${String.format("%02d", minute)} \"${commands}\"").exec().isSuccess
    }

    private val SCHEDULE_REGEXP = """^\s*([0-9]{2})([0-9]{2}): (.*)$""".toRegex()

    fun listSchedules(once: Boolean): List<Schedule> {
        return Shell.su("djs i ${if(once) 'o' else 'd'}").exec().out.filter { it.matches(SCHEDULE_REGEXP) }.map {
            val (hour, minute, command) = SCHEDULE_REGEXP.find(it)!!.destructured
            Schedule("$hour$minute", once, hour.toInt(), minute.toInt(), command)
        }
    }

    fun listAllSchedules(): List<Schedule> {
        val res = ArrayList<Schedule>(listSchedules(true))
        res.addAll(listSchedules(false))
        return res
    }

    //Charging switches
    fun listChargingSwitches(): List<String> {
        val res = Shell.su("acc -s s:").exec()
        return if(res.isSuccess) res.out.map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    fun setChargingSwitchCommand(chargingSwitch: String): String {
        return "acc -s s $chargingSwitch"
    }

    fun setChargingSwitch(chargingSwitch: String): Boolean {
        return Shell.su(setChargingSwitchCommand(chargingSwitch)).exec().isSuccess
    }

    fun testChargingSwitch(chargingSwitch: String? = null): Int {
        return Shell.su("acc -t${chargingSwitch?.let{" $it"} ?: ""}").exec().code
    }

    fun getCurrentChargingSwitch(): String? {
        val switch = SWITCH.find(readConfigToStringArray().joinToString(separator = "\n"))?.destructured?.component1()?.trim()
        return if(switch?.isNotEmpty() == true) switch else null
    }


    fun unsetChargingSwitchCommand(): String {
        return "acc -s s-"
    }

    fun unsetChargingSwitch(): Boolean {
        return Shell.su(unsetChargingSwitchCommand()).exec().isSuccess
    }

    fun setChargingLimitForOneCharge(limit: Int): Boolean {
        return Shell.su("acc -f $limit").exec().isSuccess
    }

    fun isAccInstalled(): Boolean {
        return Shell.su("which acc 1>/dev/null").exec().isSuccess
    }

    fun installAccModule(context: Context): Shell.Result? {
        try {
            val scriptFile = File(context.filesDir, "install.sh")
            val path = scriptFile.absolutePath

            BufferedInputStream(URL("https://raw.githubusercontent.com/Magisk-Modules-Repo/acc/master/install.sh").openStream())
                .use { inStream ->
                    FileOutputStream(scriptFile)
                        .use {
                            val buf = ByteArray(1024)
                            var bytesRead = inStream.read(buf, 0, 1024)

                            while (bytesRead != -1) {
                                it.write(buf, 0, bytesRead)
                                bytesRead = inStream.read(buf, 0, 1024)
                            }
                        }
                }

            return Shell.su("chmod +x $path", "sh $path").exec()
        } catch (ex: Exception) {
            ex.printStackTrace()
            return null
        }
    }
}
