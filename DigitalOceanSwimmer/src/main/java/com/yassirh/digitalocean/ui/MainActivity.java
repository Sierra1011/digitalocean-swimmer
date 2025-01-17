package com.yassirh.digitalocean.ui;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.NavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBar.LayoutParams;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.yassirh.digitalocean.R;
import com.yassirh.digitalocean.model.Account;
import com.yassirh.digitalocean.model.Droplet;
import com.yassirh.digitalocean.service.AccountService;
import com.yassirh.digitalocean.service.ActionService;
import com.yassirh.digitalocean.service.DomainService;
import com.yassirh.digitalocean.service.DropletService;
import com.yassirh.digitalocean.service.FloatingIPService;
import com.yassirh.digitalocean.service.ImageService;
import com.yassirh.digitalocean.service.RegionService;
import com.yassirh.digitalocean.service.SSHKeyService;
import com.yassirh.digitalocean.service.SizeService;
import com.yassirh.digitalocean.utils.ApiHelper;
import com.yassirh.digitalocean.utils.AppRater;
import com.yassirh.digitalocean.utils.BiometricUtils;
import com.yassirh.digitalocean.utils.MyApplication;
import com.yassirh.digitalocean.utils.MyBroadcastReceiver;
import com.yassirh.digitalocean.utils.PreferencesHelper;

import java.util.Calendar;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener, Updatable {

    private ActionBarDrawerToggle drawerToggle;

    private long lastBackPressed;
    Fragment fragment = new Fragment();
    Integer currentSelected = 0;

    Handler uiHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            update(MainActivity.this);
        }
    };

    Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
            // FIXME : figure out a better way to do this
            for (; ; ) {
                try {
                    Thread.sleep(1000);
                    Boolean update = false;
                    if (currentSelected == DrawerPositions.DROPLETS_FRAGMENT_POSITION) {
                        DropletService dropletService = new DropletService(MainActivity.this);
                        update = dropletService.requiresRefresh();
                        dropletService.setRequiresRefresh(false);
                    } else if (currentSelected == DrawerPositions.IMAGES_FRAGMENT_POSITION) {
                        ImageService imageService = new ImageService(MainActivity.this);
                        update = imageService.requiresRefresh();
                        imageService.setRequiresRefresh(false);
                    } else if (currentSelected == DrawerPositions.DOMAINS_FRAGMENT_POSITION) {
                        DomainService domainService = new DomainService(MainActivity.this);
                        update = domainService.requiresRefresh();
                        domainService.setRequiresRefresh(false);
                    } else if (currentSelected == DrawerPositions.SIZES_FRAGMENT_POSITION) {
                        SizeService sizeService = new SizeService(MainActivity.this);
                        update = sizeService.requiresRefresh();
                        sizeService.setRequiresRefresh(false);
                    } else if (currentSelected == DrawerPositions.REGIONS_FRAGMENT_POSITION) {
                        RegionService regionService = new RegionService(MainActivity.this);
                        update = regionService.requiresRefresh();
                        regionService.setRequiresRefresh(false);
                    } else if (currentSelected == DrawerPositions.SSHKEYS_FRAGMENT_POSITION) {
                        SSHKeyService sshKeyService = new SSHKeyService(MainActivity.this);
                        update = sshKeyService.requiresRefresh();
                        sshKeyService.setRequiresRefresh(false);
                    }
                    if (update)
                        uiHandler.sendMessage(new Message());

                } catch (InterruptedException ignored) {
                }
            }
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (toolbar != null) {
            ActionBar supportActionBar = getSupportActionBar();
            if (supportActionBar != null) {
                supportActionBar.setDisplayHomeAsUpEnabled(true);
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            displayBiometricPromptIfPossible();
        }

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawerToggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(drawerToggle);
        drawerToggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        navigateTo(R.id.nav_droplets);

        AccountService accountService = new AccountService(this);
        if (!accountService.hasAccounts()) {
            FragmentManager fm = getSupportFragmentManager();
            SwitchAccountDialogFragment switchAccountDialogFragment = new SwitchAccountDialogFragment();
            switchAccountDialogFragment.show(fm, "switch_account");
        }

        Account currentAccount = ApiHelper.getCurrentAccount(MyApplication.getAppContext());
        TextView accountTextView = navigationView.getHeaderView(0).findViewById(R.id.accountTextView);
        accountTextView.setText(currentAccount.getName());


        ActionService.trackActions(this);

        if (savedInstanceState == null) {
            update(this);
            t.start();
        }

        Intent myBroadcastReceiver = new Intent(this, MyBroadcastReceiver.class);
        //myBroadcastReceiver.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, myBroadcastReceiver, PendingIntent.FLAG_CANCEL_CURRENT);

        int interval = PreferencesHelper.getSynchronizationInterval(this);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (interval == 0)
            alarmManager.cancel(pendingIntent);
        else
            alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, Calendar.getInstance().getTimeInMillis(), interval * 60 * 1000, pendingIntent);

        AppRater.app_launched(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        t.interrupt();
        super.onPause();
    }

    @Override
    protected void onStop() {
        t.interrupt();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (lastBackPressed + 2000 > System.currentTimeMillis()) {
            super.onBackPressed();
        } else {
            Toast.makeText(getBaseContext(), R.string.message_press_again_to_exit, Toast.LENGTH_SHORT).show();
        }
        lastBackPressed = System.currentTimeMillis();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        FragmentManager fm;
        AlertDialog.Builder builder;
        LayoutInflater inflater;
        switch (item.getItemId()) {
            case R.id.action_sync:
                if (currentSelected == DrawerPositions.DROPLETS_FRAGMENT_POSITION) {
                    DropletService dropletService = new DropletService(this);
                    dropletService.getAllDropletsFromAPI(true, true);
                } else if (currentSelected == DrawerPositions.DOMAINS_FRAGMENT_POSITION) {
                    DomainService domainService = new DomainService(this);
                    domainService.getAllDomainsFromAPI(true);
                } else if (currentSelected == DrawerPositions.IMAGES_FRAGMENT_POSITION) {
                    ImageService imageService = new ImageService(this);
                    imageService.getAllImagesFromAPI(true);
                } else if (currentSelected == DrawerPositions.REGIONS_FRAGMENT_POSITION) {
                    RegionService regionService = new RegionService(this);
                    regionService.getAllRegionsFromAPI(true);
                } else if (currentSelected == DrawerPositions.SIZES_FRAGMENT_POSITION) {
                    SizeService sizeService = new SizeService(this);
                    sizeService.getAllSizesFromAPI(true);
                } else if (currentSelected == DrawerPositions.SSHKEYS_FRAGMENT_POSITION) {
                    SSHKeyService sshKeysService = new SSHKeyService(this);
                    sshKeysService.getAllSSHKeysFromAPI(true);
                } else if (currentSelected == DrawerPositions.FLOATING_IPS_POSITION) {
                    FloatingIPService floatingIPService = new FloatingIPService(this);
                    floatingIPService.getAllFromAPI(true);
                }
                return true;
            case R.id.action_add_droplet:
                Intent intent = new Intent(MainActivity.this, NewDropletActivity.class);
                startActivity(intent);
                return true;
            case R.id.action_add_domain:
                builder = new AlertDialog.Builder(this);
                inflater = getLayoutInflater();
                View view = inflater.inflate(R.layout.dialog_domain_create, null);
                DropletService dropletService1 = new DropletService(this);
                getResources().getString(R.string.create_domain);
                builder.setView(view);

                final EditText domainNameEditText = view.findViewById(R.id.domainNameEditText);
                final Spinner dropletSpinner = view.findViewById(R.id.dropletSpinner);
                dropletSpinner.setAdapter(new DropletAdapter(this, dropletService1.getAllDroplets()));
                builder.setPositiveButton(R.string.ok, new OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        DomainService domainService = new DomainService(MainActivity.this);
                        domainService.createDomain(domainNameEditText.getText().toString(), ((Droplet) dropletSpinner.getSelectedItem()).getNetworks().get(0).getIpAddress(), true);
                    }
                });
                builder.setNegativeButton(R.string.cancel, new OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
                builder.show().getWindow().setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                return true;
            case R.id.action_add_ssh_key:
                fm = getSupportFragmentManager();
                SSHKeyCreateDialogFragment sshKeyCreateDialogFragment = new SSHKeyCreateDialogFragment();
                sshKeyCreateDialogFragment.show(fm, "create_ssh_key");
                return true;
            case R.id.action_add_record:
                fm = getSupportFragmentManager();
                RecordCreateDialogFragment recordCreateDialogFragment = new RecordCreateDialogFragment();
                recordCreateDialogFragment.show(fm, "create_record");
                return true;
            case R.id.action_switch_account:
                fm = getSupportFragmentManager();
                SwitchAccountDialogFragment switchAccountDialogFragment = new SwitchAccountDialogFragment();
                switchAccountDialogFragment.show(fm, "switch_account");
                return true;
            case R.id.action_settings:
                intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                finish();
                return true;
            case R.id.action_about:
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://yassirh.com/digitalocean_swimmer/"));
                startActivity(browserIntent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private void displayBiometricPromptIfPossible() {
        if (BiometricUtils.isSdkVersionSupported()
                && BiometricUtils.isBiometricsEnabled(this)
                && BiometricUtils.isPermissionGranted(this)
                && BiometricUtils.isFingerprintAvailable(this)
                && BiometricUtils.isHardwareSupported(this)) {
            BiometricPrompt build = new BiometricPrompt.Builder(getApplicationContext())
                    .setTitle("Authentication")
                    .setNegativeButton("Cancel", getApplication().getMainExecutor(), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            MainActivity.this.finish();
                        }
                    })
                    .build();
            build.authenticate(new CancellationSignal(), MainActivity.this.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode == 10) {
                        MainActivity.this.finish();
                    }
                }
            });
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();
        return navigateTo(id);
    }

    private boolean navigateTo(int menuId) {
        Fragment fragment = null;

        Class fragmentClass;

        if (menuId == R.id.nav_droplets) {
            this.setTitle("Droplets");
            currentSelected = DrawerPositions.DROPLETS_FRAGMENT_POSITION;
            fragmentClass = DropletsFragment.class;
        } else if (menuId == R.id.nav_domains) {
            this.setTitle("Domains");
            currentSelected = DrawerPositions.DOMAINS_FRAGMENT_POSITION;
            fragmentClass = DomainsFragment.class;
        } else if (menuId == R.id.nav_images) {
            this.setTitle("Images");
            fragmentClass = ImagesFragment.class;
            currentSelected = DrawerPositions.IMAGES_FRAGMENT_POSITION;
        } else if (menuId == R.id.nav_sizes) {
            this.setTitle("Size");
            fragmentClass = SizesFragment.class;
            currentSelected = DrawerPositions.SIZES_FRAGMENT_POSITION;
        } else if (menuId == R.id.nav_regions) {
            this.setTitle("Regions");
            currentSelected = DrawerPositions.REGIONS_FRAGMENT_POSITION;
            fragmentClass = RegionsFragment.class;
        } else if (menuId == R.id.nav_keys) {
            this.setTitle("SSH Keys");
            currentSelected = DrawerPositions.SSHKEYS_FRAGMENT_POSITION;
            fragmentClass = SSHKeyFragment.class;
        } else {
            fragmentClass = Fragment.class;
        }

        try {
            fragment = (Fragment) fragmentClass.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }

        FragmentManager fragmentManager = getSupportFragmentManager();

        fragmentManager.beginTransaction().replace(R.id.flContent, fragment).commit();

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }


    @Override
    public void setTitle(CharSequence title) {
        ActionBar supportActionBar = getSupportActionBar();
        if (supportActionBar != null) {
            supportActionBar.setTitle(title);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
        drawerToggle.syncState();
        update(this);
    }

    @Override
    public void update(Context context) {
        try {
            if (fragment instanceof Updatable) {
                ((Updatable) fragment).update(context);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
