package org.jupiterns.drivetime

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Bluetooth device picker for the car-stereo / OBD-dongle settings — extracted whole
 * from WebViewActivity. Lists bonded devices *and* actively discovers nearby ones (how
 * Torque finds a dongle that never appears in system Bluetooth settings), self-grants
 * the CONNECT/SCAN permissions on first use, and offers manual MAC entry.
 *
 * Construct in the Activity's onCreate (it registers a permission launcher, which must
 * happen before STARTED) and call [close] from onDestroy — if the activity dies while
 * the dialog is open (rotation), discovery and the receiver are torn down instead of
 * leaking a scan that drains the battery.
 */
class BtDevicePicker(
    private val activity: AppCompatActivity,
    private val toast: (String) -> Unit,
) {
    private var pendingPick: (() -> Unit)? = null
    private var openDialog: AlertDialog? = null
    private var cleanup: (() -> Unit)? = null

    private val permLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val resume = pendingPick
            pendingPick = null
            if (grants.values.all { it }) resume?.invoke()
            else toast("Bluetooth permission is needed to find your dongle")
        }

    fun pick(title: String, onPick: (mac: String, name: String) -> Unit, onClear: () -> Unit) {
        if (!hasBtPerms()) {
            // Request inline — the picker must self-grant CONNECT + SCAN the first time.
            pendingPick = { pick(title, onPick, onClear) }
            permLauncher.launch(btPerms())
            return
        }
        val adapter = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { toast("No Bluetooth on this device"); return }
        showScanningPicker(title, adapter, onPick, onClear)
    }

    /** Tear down an open picker (dialog + discovery + receiver). Safe to call anytime. */
    fun close() {
        cleanup?.invoke()
        cleanup = null
        runCatching { openDialog?.dismiss() }
        openDialog = null
    }

    /**
     * Live device picker: bonded devices up front, discovered ones appended as they're
     * found. Discovery + the receiver are torn down when the dialog closes (or via [close]).
     */
    @SuppressLint("MissingPermission")
    private fun showScanningPicker(
        title: String,
        adapter: BluetoothAdapter,
        onPick: (mac: String, name: String) -> Unit,
        onClear: () -> Unit,
    ) {
        val seen = HashSet<String>()
        val picks = ArrayList<Pair<String, String>>()       // (mac, name), parallel to rows
        val rows = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        var nearbyCount = 0
        var dialog: AlertDialog? = null

        fun setStatus(scanning: Boolean) {
            dialog?.setTitle(when {
                scanning -> "$title — scanning…"
                picks.isEmpty() -> "$title — none found. Rescan, or Enter MAC."
                nearbyCount == 0 -> "$title — tap a paired device, or Rescan / Enter MAC"
                else -> "$title — tap your device"
            })
        }

        fun add(mac: String?, name: String?, nearby: Boolean) {
            if (mac == null || !seen.add(mac)) return
            val nm = name?.takeIf { it.isNotBlank() } ?: mac
            picks.add(mac to nm)
            rows.add("$nm\n$mac · ${if (nearby) "nearby" else "paired"}")   // auto-refreshes
            if (nearby) nearbyCount++
        }

        adapter.bondedDevices?.forEach { add(it.address, it.name, nearby = false) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            ?.let { add(it.address, it.name, nearby = true) }
                        setStatus(scanning = true)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> setStatus(scanning = false)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            .apply { addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        cleanup = {
            runCatching { adapter.cancelDiscovery() }
            runCatching { activity.unregisterReceiver(receiver) }
        }

        fun startScan() {
            runCatching { adapter.cancelDiscovery() }
            runCatching { adapter.startDiscovery() }
            setStatus(scanning = true)
        }

        val d = AlertDialog.Builder(activity)
            .setTitle("$title — scanning…")
            .setAdapter(rows) { _, i -> onPick(picks[i].first, picks[i].second) }
            .setPositiveButton("Rescan", null)              // overridden below so it doesn't dismiss
            .setNeutralButton("Clear") { _, _ -> onClear() }
            .setNegativeButton("Enter MAC") { _, _ -> promptForMac(title, onPick) }
            .setOnDismissListener {
                cleanup?.invoke()
                cleanup = null
                openDialog = null
            }
            .create()
        dialog = d
        openDialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { startScan() }
        startScan()
    }

    private fun hasBtPerms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else emptyArray()

    /** Manual MAC entry — the path for an unbonded dongle that never appears in the paired
     *  list. LocationService connects by MAC via the insecure-socket fallback, no bonding. */
    private fun promptForMac(title: String, onPick: (mac: String, name: String) -> Unit) {
        val input = EditText(activity).apply { hint = "AA:BB:CC:DD:EE:FF"; setSingleLine() }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("Enter the adapter's Bluetooth MAC (from Torque, the dongle's label, or a BT scanner app).")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val mac = input.text.toString().trim().uppercase()
                if (BluetoothAdapter.checkBluetoothAddress(mac)) onPick(mac, mac)
                else toast("Invalid MAC — expected AA:BB:CC:DD:EE:FF")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
