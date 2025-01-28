package com.example.survey





import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
 
class MainActivity : AppCompatActivity() {
    private val REQUEST_WRITE_LEGACY = 100 // For API ≤ 28
    private val REQUEST_READ_LEGACY = 101  // For API ≤ 28

    // UI
    private lateinit var etName: EditText
    private lateinit var etAge: EditText
    private lateinit var tvAddress: TextView
    private lateinit var tvResults: TextView

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var address: String? = null

    private var peopleArray = JSONArray()

    // Constants
    companion object {
        private const val LOC_PERMISSION_REQ_CODE = 1
        private const val EXT_WRITE_PERMISSION_REQ_CODE = 2

        private const val PREF_KEY = "people_list"
        private const val SHARED_PREF_NAME = "people_data"

        private const val INTERNAL_FILE_NAME = "people_data_internal.json"
        private const val EXTERNAL_FILE_NAME = "people_data_external.json"

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init UI
        etName = findViewById(R.id.etName)
        etAge = findViewById(R.id.etAge)
        tvAddress = findViewById(R.id.tvAddress)
        tvResults = findViewById(R.id.tvResults)

        //  Location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val btnFetchLocation = findViewById<Button>(R.id.btnFetchLocation)
        val btnAddPerson = findViewById<Button>(R.id.btnAddPerson)
        val btnShowShared = findViewById<Button>(R.id.btnShowShared)
        val btnShowInternal = findViewById<Button>(R.id.btnShowInternal)
        val btnShowExternal = findViewById<Button>(R.id.btnShowExternal)

        // Fetch location
        btnFetchLocation.setOnClickListener {
            fetchLocation()
        }

        // Load existing data from SharedPrefs into memory at the beginning
        loadFromSharedPrefsIntoMemory()

        // Add Person & save to all 3 storages
        btnAddPerson.setOnClickListener {
            addPerson()
        }

        // Show from Shared Prefs
        btnShowShared.setOnClickListener {
            showFromSharedPrefs()
        }

        // Show from Internal Storage
        btnShowInternal.setOnClickListener {
            showFromInternal()
        }

        // Show from External Storage
        btnShowExternal.setOnClickListener {
            showFromExternal()
        }
    }


    // location

    private fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            // Request Fine Location
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOC_PERMISSION_REQ_CODE
            )
        } else {
            checkLocationServicesAndGetLocation()
        }
    }

    private fun checkLocationServicesAndGetLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val gpsEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val networkEnabled = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !networkEnabled) {
            Toast.makeText(this, "Please enable location services", Toast.LENGTH_SHORT).show()
            startActivity(android.content.Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else {
            getLastKnownLocation()
        }
    }

    private fun getLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                reverseGeocodeAddress(location)
            } else {
                requestLocationUpdates()
            }
        }
    }

    private fun requestLocationUpdates() {
        val locRequest = LocationRequest.create().apply {
            priority = Priority.PRIORITY_HIGH_ACCURACY
            interval = 10_000
            fastestInterval = 5_000
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            return
        }
        fusedLocationClient.requestLocationUpdates(
            locRequest,
            object : LocationCallback() {
                override fun onLocationResult(locResult: LocationResult) {
                    if (locResult.locations.isNotEmpty()) {
                        val loc = locResult.locations[0]
                        reverseGeocodeAddress(loc)
                        fusedLocationClient.removeLocationUpdates(this)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Unable to fetch location",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            null
        )
    }

    private fun reverseGeocodeAddress(location: Location) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                address = addresses[0].getAddressLine(0)
                tvAddress.text = "Address: $address"
            } else {
                address = null
                tvAddress.text = "No address found"
            }
        } catch (e: Exception) {
            address = null
            tvAddress.text = "Geocoder error: ${e.message}"
        }
    }


    // Add person and save all 3 storages

    private fun addPerson() {
        val name = etName.text.toString().trim()
        val age = etAge.text.toString().trim()

        if (name.isBlank() || age.isBlank() || address == null) {
            Toast.makeText(this, "Please fill Name, Age, and Fetch Location!", Toast.LENGTH_SHORT)
                .show()
            return
        }

        // Create JSON for this person
        val personObj = JSONObject()
        personObj.put("name", name)
        personObj.put("age", age)
        personObj.put("address", address)

        // Add to in-memory array
        peopleArray.put(personObj)

        // Save to Shared Prefs
        saveToSharedPrefs()

        // Save to Internal Storage
        saveToInternal()

        // Save to External Storage (if permitted)
        checkAndSaveExternal()

        // Clear fields
        etName.text.clear()
        etAge.text.clear()
        tvAddress.text = "Address will appear here..."
        address = null

        Toast.makeText(
            this,
            "Person added. Data saved to Shared, Internal, and External!",
            Toast.LENGTH_SHORT
        ).show()
    }


    // -----------------------------------------------shared preferences----------------------------------------------

    private fun saveToSharedPrefs() {
        val prefs = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_KEY, peopleArray.toString())
            .apply()
    }

    private fun loadFromSharedPrefsIntoMemory() {
        val prefs = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(PREF_KEY, "[]") // Default to empty array
        peopleArray = JSONArray(jsonStr)
    }

    private fun showFromSharedPrefs() {
        val prefs = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(PREF_KEY, "[]") ?: "[]"
        val arr = JSONArray(jsonStr)

        // Display
        val sb = StringBuilder("Data from Shared Preferences:\n\n")
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            sb.append("Person #${i+1}:\n")
            sb.append(" - Name: ${obj.optString("name")}\n")
            sb.append(" - Age: ${obj.optString("age")}\n")
            sb.append(" - Address: ${obj.optString("address")}\n\n")
        }
        tvResults.text = if (arr.length() > 0) sb.toString() else "No data in Shared Prefs."
    }


    // -------------------------------------------------------internal storage-------------------------------------------------

    private fun saveToInternal() {
        val dataStr = peopleArray.toString()
        openFileOutput(INTERNAL_FILE_NAME, Context.MODE_PRIVATE).use { fos ->
            fos.write(dataStr.toByteArray())
        }
    }

    private fun showFromInternal() {
        try {
            openFileInput(INTERNAL_FILE_NAME).use { fis ->
                val content = fis.bufferedReader().readText()
                val arr = JSONArray(content)

                // Show internal path
                val filePath = "${filesDir.absolutePath}/$INTERNAL_FILE_NAME"
                val sb = StringBuilder("Data from Internal:\n$filePath\n\n")

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    sb.append("Person #${i+1}:\n")
                    sb.append(" - Name: ${obj.optString("name")}\n")
                    sb.append(" - Age: ${obj.optString("age")}\n")
                    sb.append(" - Address: ${obj.optString("address")}\n\n")
                }
                tvResults.text = jsonArrayToString(arr, "Internal Storage\nPath: $filePath")

            }
        } catch (e: Exception) {
            tvResults.text = "Error reading internal file: ${e.message}"
        }
    }


    // -------------------------------------------------------external storage--------------------------------------------------------

    private fun checkAndSaveExternal() {
        requestAllFilesPermission()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 or lower => request the old WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_WRITE_LEGACY
                )
            } else {
                saveToExternal()
            }
        } else {
            // Android 10+ =>  use Scoped Storage or All Files Access

            if (hasAllFilesAccess()) {
                saveToExternal()
            } else {
                Toast.makeText(this, "Need All Files Access on Android 10+!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToExternal() {
        if (!isExternalStorageWritable()) {
            Toast.makeText(this, "External storage not writable!", Toast.LENGTH_SHORT).show()
            return
        }
        val data = peopleArray.toString()

        // Public Documents folder (requires permission or All Files Access)
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (!docsDir.exists()) docsDir.mkdirs()

        val outFile = File(docsDir, EXTERNAL_FILE_NAME)
        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(data.toByteArray())
            }
            Toast.makeText(this, "Saved to: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            tvResults.text = "Error writing external: ${e.message}"
        }
    }


    private fun showFromExternal() {
        // checking if external storage is readable
        if (!isExternalStorageReadable()) {
            Toast.makeText(this, "External storage not readable!", Toast.LENGTH_SHORT).show()
            return
        }

        // locating  the file in the public Documents folder
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val inFile = File(docsDir, EXTERNAL_FILE_NAME)
        if (!inFile.exists()) {
            tvResults.text = "No external file found."
            return
        }

        // reading file contents
        try {
            val text = FileInputStream(inFile).bufferedReader().readText()
            val jsonArray = JSONArray(text)

            // converting json to human readable form
            val sb = StringBuilder("Data from External Storage\nPath: ${inFile.absolutePath}\n\n")
            for (i in 0 until jsonArray.length()) {
                val personObj = jsonArray.getJSONObject(i)
                val name = personObj.optString("name", "N/A")
                val age = personObj.optString("age", "N/A")
                val address = personObj.optString("address", "N/A")

                sb.append("Person #${i + 1}:\n")
                sb.append(" - Name: $name\n")
                sb.append(" - Age: $age\n")
                sb.append(" - Address: $address\n\n")
            }


            tvResults.text = sb.toString()

        } catch (e: Exception) {
            tvResults.text = "Error reading external: ${e.message}"
        }
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // If already have the  manage permission
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Already have All Files Access!", Toast.LENGTH_SHORT).show()
            } else {
                // user must manually grant "All Files Access"
                val uri = Uri.parse("package:$packageName")
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "All Files Access is only relevant on Android 11+.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {

            true
        }
    }


    // permission result callback

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty()) return

        when (requestCode) {
            REQUEST_WRITE_LEGACY -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    saveToExternal()
                } else {
                    Toast.makeText(this, "Write permission denied (legacy).", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_READ_LEGACY -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showFromExternal()
                } else {
                    Toast.makeText(this, "Read permission denied (legacy).", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // check external storage availability
    private fun isExternalStorageWritable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    private fun isExternalStorageReadable(): Boolean {
        val state = Environment.getExternalStorageState()
        return (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY)
    }
    //  json to text
    private fun jsonArrayToString(jsonArray: JSONArray, sourceTitle: String): String {
        val sb = StringBuilder("Data from $sourceTitle:\n\n")
        for (i in 0 until jsonArray.length()) {
            val personObj = jsonArray.getJSONObject(i)
            val name = personObj.optString("name", "N/A")
            val age = personObj.optString("age", "N/A")
            val address = personObj.optString("address", "N/A")

            sb.append("Person #${i + 1}:\n")
            sb.append(" - Name: $name\n")
            sb.append(" - Age: $age\n")
            sb.append(" - Address: $address\n\n")
        }
        return sb.toString()
    }
}
