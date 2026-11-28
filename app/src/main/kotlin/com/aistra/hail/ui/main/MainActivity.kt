package com.aistra.hail.ui.main

import com.aistra.hail.ui.DeviceUnlock
import com.aistra.hail.ui.protectContent
import android.os.Bundle
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.aistra.hail.R
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ActivityMainBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HUI
import com.aistra.hail.utils.ShizukuConnection
import com.google.android.material.appbar.AppBarLayout
import com.aistra.hail.ui.PrivateDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity(), NavController.OnDestinationChangedListener {
    lateinit var fab: ExtendedFloatingActionButton
    lateinit var appbar: AppBarLayout

    private val unlock = DeviceUnlock(this)
    private lateinit var binding: ActivityMainBinding
    private var authenticated = false
    private var authenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        window.protectContent()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = initView()
        binding.root.isVisible = !HailData.biometricLogin
    }

    override fun onStart() {
        super.onStart()
        ShizukuConnection.refreshAsync()
        if (!HailData.biometricLogin || authenticated) {
            binding.root.isVisible = true
        } else if (!authenticating) {
            binding.root.isVisible = false
            authenticating = true
            unlock.request { allowed ->
                authenticating = false
                authenticated = allowed
                if (allowed) binding.root.isVisible = true else finishAndRemoveTask()
            }
        }
    }

    override fun onStop() {
        if (HailData.biometricLogin) {
            binding.root.isVisible = false
            authenticated = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        app.repairShortcutIconsOnce()
    }

    private fun initView() = ActivityMainBinding.inflate(layoutInflater).apply {
        setContentView(root)
        setSupportActionBar(appBarMain.toolbar)
        fab = appBarMain.fab
        appbar = appBarMain.appBarLayout

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        navController.addOnDestinationChangedListener(this@MainActivity)
        val appBarConfiguration = AppBarConfiguration.Builder(
            R.id.nav_home, R.id.nav_apps, R.id.nav_settings, R.id.nav_about
        ).build()
        setupActionBarWithNavController(navController, appBarConfiguration)
        bottomNav?.setupWithNavController(navController)
        navRail?.setupWithNavController(navController)

        val isRtl = isRtl
        val isLandscape = isLandscape
        appBarMain.appBarLayout.applyDefaultInsetter {
            paddingRelative(isRtl, start = !isLandscape, end = true, top = true)
        }
        bottomNav?.applyDefaultInsetter { paddingRelative(isRtl, start = true, end = true, bottom = true) }
        navRail?.applyDefaultInsetter { paddingRelative(isRtl, start = true, top = true, bottom = true) }
        fab.applyDefaultInsetter { marginRelative(isRtl, end = true, bottom = isLandscape) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.let { MenuCompat.setGroupDividerEnabled(it, true) }
        return super.onCreateOptionsMenu(menu)
    }

    fun ownerRemoveDialog() {
        PrivateDialogBuilder(this).setTitle(R.string.title_remove_owner).setMessage(R.string.msg_remove_owner)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                HPolicy.setOrganizationName()
                HPolicy.removeDeviceOwner()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /* override fun onStop() {
        super.onStop()
        if (HailData.biometricLogin) finishAndRemoveTask()
    } */

    override fun onDestinationChanged(
        controller: NavController, destination: NavDestination, arguments: Bundle?
    ) {
        fab.tag = destination.id == R.id.nav_home
        if (fab.tag == true) fab.show() else fab.hide()
    }
}
