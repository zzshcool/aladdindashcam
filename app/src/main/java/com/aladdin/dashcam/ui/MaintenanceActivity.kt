package com.aladdin.dashcam.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aladdin.dashcam.R
import com.aladdin.dashcam.maintenance.MaintenanceRecord
import com.aladdin.dashcam.maintenance.MaintenanceRepository
import kotlinx.coroutines.*
import java.io.File
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.UUID

class MaintenanceActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private lateinit var repository: MaintenanceRepository
    private var editor: AlertDialog? = null
    private var loading: Job? = null
    private var registeredPlates: List<String> = emptyList()
    private var selectedPlate = ""
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun money(value: BigDecimal) = NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance("TWD")
        minimumFractionDigits = 0; maximumFractionDigits = 2
    }.format(value)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MaintenanceRepository(File(filesDir, "maintenance.json"))
        selectedPlate = savedInstanceState?.getString("plate") ?: getPreferences(MODE_PRIVATE).getString("plate", "").orEmpty()
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(rows) })
        AppAppearance.applyContentInsets(rows, 16, 8, 16, 16)
        render()
    }
    private fun action(label: Int, click: () -> Unit) = com.google.android.material.button.MaterialButton(this).apply {
        setText(label); isAllCaps = false; minHeight = dp(48); cornerRadius = dp(12)
        insetTop = 0; insetBottom = 0
        elevation = 0f; stateListAnimator = null
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { click() }
    }
    private fun label(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setPadding(0, dp(8), 0, dp(8))
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
    }
    private fun card(content: View) = com.google.android.material.card.MaterialCardView(this).apply {
        radius = dp(18).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
        strokeColor = ContextCompat.getColor(context, R.color.border_subtle)
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
        addView(content)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }
    private fun caption(value: String) = label(value, 12f).apply {
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun render() {
        loading?.cancel()
        rows.removeAllViews()
        rows.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_live_back)
                imageTintList = ContextCompat.getColorStateList(context, R.color.text_primary)
                setBackgroundResource(android.R.drawable.list_selector_background)
                contentDescription = getString(R.string.maintenance_back)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(label(getString(R.string.maintenance_title), 21f).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        loading = lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.snapshot() }
                registeredPlates = data.plates
                val groups = data.plates + if (data.records.any { it.vehiclePlate.isEmpty() }) listOf("") else emptyList()
                if (selectedPlate !in groups) selectedPlate = groups.firstOrNull().orEmpty()
                val selector = Spinner(this@MaintenanceActivity).apply {
                    val choices = if (groups.isEmpty()) listOf(getString(R.string.vehicle_none)) else groups.map { plateLabel(it) }
                    adapter = object : ArrayAdapter<String>(this@MaintenanceActivity, android.R.layout.simple_spinner_dropdown_item, choices) {
                        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                            (super.getView(position, convertView, parent) as TextView).apply {
                                textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                                setPadding(0, dp(8), dp(8), dp(8))
                            }
                    }
                    setSelection(groups.indexOf(selectedPlate).coerceAtLeast(0))
                    isEnabled = groups.isNotEmpty()
                    contentDescription = getString(R.string.vehicle_select)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            val chosen = groups.getOrNull(position) ?: return
                            if (chosen != selectedPlate) {
                                selectedPlate = chosen
                                getPreferences(MODE_PRIVATE).edit().putString("plate", chosen).apply()
                                render()
                            }
                        }
                    }
                }
                val records = data.records.filter { it.vehiclePlate == selectedPlate }
                val total = records.fold(BigDecimal.ZERO) { sum, record -> sum + record.cost }
                val vehicle = LinearLayout(this@MaintenanceActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
                    addView(caption(getString(R.string.maintenance_vehicle)))
                    addView(LinearLayout(context).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(selector, LinearLayout.LayoutParams(0, dp(52), 1f))
                        addView(ImageButton(context).apply {
                            setImageResource(R.drawable.ic_tab_device)
                            imageTintList = ContextCompat.getColorStateList(context, R.color.text_secondary)
                            setBackgroundResource(android.R.drawable.list_selector_background)
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            contentDescription = getString(R.string.vehicle_manage)
                            tooltipText = contentDescription
                            setOnClickListener { managePlates(data.records) }
                        }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    })
                }
                rows.addView(card(vehicle))
                val overview = LinearLayout(this@MaintenanceActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(14))
                    addView(caption(getString(R.string.maintenance_spent)))
                    addView(label(money(total), 30f).apply {
                        setPadding(0, dp(4), 0, dp(4))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                    })
                    addView(View(context).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.border_subtle)) },
                        LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
                    val metrics = LinearLayout(context)
                    for ((title, value) in listOf(
                        getString(R.string.maintenance_visits) to getString(R.string.maintenance_count, records.size),
                        getString(R.string.maintenance_last_mileage) to (records.firstOrNull()?.let {
                            NumberFormat.getIntegerInstance().format(it.odometerKm) + " km"
                        } ?: "—")
                    )) {
                        metrics.addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(caption(title))
                            addView(label(value, 18f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, dp(4)) })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    addView(metrics)
                    records.firstOrNull()?.let { addView(caption(getString(R.string.maintenance_last_date,
                        it.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))) }
                }
                rows.addView(card(overview))
                rows.addView(com.google.android.material.button.MaterialButton(this@MaintenanceActivity).apply {
                    setText(if (selectedPlate.isNotEmpty()) R.string.maintenance_add else if (registeredPlates.isEmpty()) R.string.vehicle_add else R.string.vehicle_select)
                    isAllCaps = false; cornerRadius = dp(14); insetTop = 0; insetBottom = 0
                    backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent_blue)
                    setTextColor(ContextCompat.getColor(context, R.color.accent_blue_dark))
                    setIconResource(android.R.drawable.ic_input_add)
                    iconTint = ContextCompat.getColorStateList(context, R.color.accent_blue_dark)
                    iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                    iconSize = dp(18)
                    setOnClickListener {
                        if (selectedPlate.isNotEmpty()) edit(null)
                        else if (registeredPlates.isEmpty()) editPlate(null) else selector.performClick()
                    }
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16); bottomMargin = dp(12) })
                rows.addView(label(getString(R.string.maintenance_history), 17f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
                if (selectedPlate.isEmpty()) rows.addView(caption(getString(if (data.plates.isEmpty()) R.string.vehicle_need_plate else R.string.vehicle_legacy_hint)))
                if (records.isEmpty()) {
                    rows.addView(card(LinearLayout(this@MaintenanceActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                        setPadding(dp(24), dp(20), dp(24), dp(20))
                        addView(ImageView(context).apply {
                            setImageResource(R.drawable.ic_maintenance_service)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(dp(40), dp(40)))
                        addView(label(getString(R.string.maintenance_empty_title), 17f).apply {
                            gravity = android.view.Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
                        })
                        addView(caption(getString(R.string.maintenance_empty_body)).apply { gravity = android.view.Gravity.CENTER })
                    }))
                }
                records.forEach { record ->
                    val content = LinearLayout(this@MaintenanceActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
                        addView(LinearLayout(context).apply {
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            addView(caption(record.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(label(money(record.cost), 19f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
                        })
                        addView(label(record.description, 17f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
                        addView(LinearLayout(context).apply {
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            addView(caption(getString(R.string.maintenance_mileage) + " · " + NumberFormat.getIntegerInstance().format(record.odometerKm)),
                                LinearLayout.LayoutParams(0, -2, 1f))
                            addView(ImageButton(context).apply {
                                setImageResource(R.drawable.ic_live_more)
                                imageTintList = ContextCompat.getColorStateList(context, R.color.text_secondary)
                                setBackgroundResource(android.R.drawable.list_selector_background)
                                contentDescription = getString(R.string.maintenance_record_options)
                                setPadding(dp(12), dp(12), dp(12), dp(12))
                                setOnClickListener { anchor ->
                                    androidx.appcompat.widget.PopupMenu(this@MaintenanceActivity, anchor).apply {
                                        menu.add(0, 1, 0, R.string.maintenance_edit)
                                        menu.add(0, 2, 1, R.string.maintenance_delete)
                                        setOnMenuItemClickListener { item ->
                                            if (item.itemId == 1) edit(record) else {
                                                AlertDialog.Builder(this@MaintenanceActivity).setTitle(R.string.maintenance_delete)
                                                    .setMessage(record.description).setNegativeButton(android.R.string.cancel, null)
                                                    .setPositiveButton(R.string.maintenance_delete) { _, _ ->
                                                        lifecycleScope.launch {
                                                            try { withContext(Dispatchers.IO) { repository.delete(record.id) }; render() }
                                                            catch (error: Exception) { showError(error) }
                                                        }
                                                    }.show()
                                            }
                                            true
                                        }
                                    }.show()
                                }
                            }, LinearLayout.LayoutParams(dp(48), dp(48)))
                        })
                    }
                    rows.addView(card(content).apply {
                        isClickable = true; isFocusable = true
                        setOnClickListener { edit(record) }
                    })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { rows.addView(label(getString(R.string.maintenance_load_failed))); showError(error) }
        }
    }
    private fun plateLabel(plate: String) = plate.ifEmpty { getString(R.string.vehicle_unassigned) }

    private fun managePlates(records: List<MaintenanceRecord>) {
        val choices = listOf(getString(R.string.vehicle_add)) + registeredPlates
        editor = AlertDialog.Builder(this).setTitle(R.string.vehicle_manage)
            .setItems(choices.toTypedArray()) { _, index ->
                if (index == 0) editPlate(null) else {
                    val plate = registeredPlates[index - 1]
                    editor = AlertDialog.Builder(this).setTitle(plate)
                        .setItems(arrayOf(getString(R.string.vehicle_rename), getString(R.string.vehicle_remove))) { _, action ->
                            if (action == 0) editPlate(plate)
                            else if (records.any { it.vehiclePlate == plate }) {
                                Toast.makeText(this, R.string.vehicle_has_records, Toast.LENGTH_LONG).show()
                            } else {
                                editor = AlertDialog.Builder(this).setTitle(R.string.vehicle_remove).setMessage(plate)
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(R.string.maintenance_delete) { _, _ ->
                                        lifecycleScope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { repository.deletePlate(plate) }
                                                if (selectedPlate == plate) selectedPlate = ""
                                                render()
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (error: Exception) { showError(error) }
                                        }
                                    }.show()
                            }
                        }.show()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun editPlate(old: String?) {
        val field = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.vehicle_plate)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true); setText(old.orEmpty())
            background = null
            setPadding(dp(14), dp(16), dp(14), dp(16))
        }
        val form = LinearLayout(this).apply {
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(com.google.android.material.textfield.TextInputLayout(context).apply {
                boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
                addView(field)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        editor = AlertDialog.Builder(this).setTitle(if (old == null) R.string.vehicle_add else R.string.vehicle_rename)
            .setView(form).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.maintenance_save, null).create().also { dialog ->
                dialog.setOnShowListener {
                    val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    save.setOnClickListener {
                        val plate = MaintenanceRecord.normalizePlate(field.text.toString())
                        if (!MaintenanceRecord.validPlate(plate)) { field.error = getString(R.string.vehicle_invalid); return@setOnClickListener }
                        if (plate != old && plate in registeredPlates) { field.error = getString(R.string.vehicle_duplicate); return@setOnClickListener }
                        save.isEnabled = false
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (old == null) repository.addPlate(plate) else repository.renamePlate(old, plate)
                                }
                                selectedPlate = plate
                                getPreferences(MODE_PRIVATE).edit().putString("plate", plate).apply()
                                dialog.dismiss(); render()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { save.isEnabled = true; showError(error) }
                        }
                    }
                }; dialog.show()
            }
    }

    private fun edit(record: MaintenanceRecord?) {
        var date = record?.date ?: LocalDate.now()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val plateChoices = (if (record != null && record.vehiclePlate.isEmpty()) listOf("") else emptyList()) + registeredPlates
        form.addView(label(getString(R.string.vehicle_plate)))
        val vehicle = Spinner(this).apply {
            adapter = ArrayAdapter(this@MaintenanceActivity, android.R.layout.simple_spinner_dropdown_item, plateChoices.map { plateLabel(it) })
            setSelection(plateChoices.indexOf(record?.vehiclePlate ?: selectedPlate).coerceAtLeast(0))
            contentDescription = getString(R.string.vehicle_select)
        }
        form.addView(vehicle)
        fun input(title: Int, value: String, type: Int): EditText {
            val field = com.google.android.material.textfield.TextInputEditText(this).apply {
                inputType = type; setText(value)
                background = null
                setPadding(dp(14), dp(16), dp(14), dp(16))
            }
            form.addView(com.google.android.material.textfield.TextInputLayout(this).apply {
                hint = getString(title)
                boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
                addView(field)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return field
        }
        val dateButton = action(R.string.maintenance_date) { }.apply {
            text = date.toString()
            strokeWidth = dp(1); strokeColor = ContextCompat.getColorStateList(context, R.color.border_subtle)
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.surface_dark)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setOnClickListener {
                DatePickerDialog(this@MaintenanceActivity, { _, y, m, d ->
                    date = LocalDate.of(y, m + 1, d); text = date.toString()
                }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }
        }
        form.addView(label(getString(R.string.maintenance_date))); form.addView(dateButton)
        val mileage = input(R.string.maintenance_mileage, record?.odometerKm?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val cost = input(R.string.maintenance_cost, record?.cost?.toPlainString().orEmpty(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val description = input(R.string.maintenance_description, record?.description.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        description.minLines = 2
        editor = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setBackground(android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@MaintenanceActivity, R.color.surface_dark))
                cornerRadius = dp(24).toFloat()
            }).setTitle(if (record == null) R.string.maintenance_add else R.string.maintenance_edit)
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.maintenance_save, null).create().also { dialog ->
                dialog.setOnShowListener {
                    val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    save.setOnClickListener {
                        val km = mileage.text.toString().toLongOrNull()?.takeIf { it >= 0 }
                        val cents = runCatching { MaintenanceRecord.parseCost(cost.text.toString()) }.getOrNull()
                        val text = description.text.toString().trim()
                        val plate = plateChoices.getOrNull(vehicle.selectedItemPosition).orEmpty()
                        if (record == null && plate.isEmpty()) { Toast.makeText(this, R.string.vehicle_need_plate, Toast.LENGTH_LONG).show(); return@setOnClickListener }
                        if (km == null) { mileage.error = getString(R.string.maintenance_invalid_mileage); return@setOnClickListener }
                        if (cents == null) { cost.error = getString(R.string.maintenance_invalid_cost); return@setOnClickListener }
                        if (text.isEmpty()) { description.error = getString(R.string.maintenance_required); return@setOnClickListener }
                        save.isEnabled = false
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { repository.save(MaintenanceRecord(record?.id ?: UUID.randomUUID().toString(), date, km, cents, text, plate)) }
                                dialog.dismiss(); render()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { save.isEnabled = true; showError(error) }
                        }
                    }
                }; dialog.show()
            }
    }
    private fun showError(error: Exception) {
        android.util.Log.e("Maintenance", "Maintenance storage failed", error)
        Toast.makeText(this, R.string.maintenance_storage_failed, Toast.LENGTH_LONG).show()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("plate", selectedPlate) }
    override fun onDestroy() { editor?.dismiss(); super.onDestroy() }
}
