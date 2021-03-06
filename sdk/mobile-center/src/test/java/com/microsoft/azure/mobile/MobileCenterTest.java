package com.microsoft.azure.mobile;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.microsoft.azure.mobile.channel.Channel;
import com.microsoft.azure.mobile.channel.DefaultChannel;
import com.microsoft.azure.mobile.ingestion.models.CustomPropertiesLog;
import com.microsoft.azure.mobile.ingestion.models.StartServiceLog;
import com.microsoft.azure.mobile.ingestion.models.WrapperSdk;
import com.microsoft.azure.mobile.ingestion.models.json.LogFactory;
import com.microsoft.azure.mobile.utils.DeviceInfoHelper;
import com.microsoft.azure.mobile.utils.IdHelper;
import com.microsoft.azure.mobile.utils.MobileCenterLog;
import com.microsoft.azure.mobile.utils.ShutdownHelper;
import com.microsoft.azure.mobile.utils.storage.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.microsoft.azure.mobile.persistence.DatabasePersistenceAsync.THREAD_NAME;
import static com.microsoft.azure.mobile.utils.PrefStorageConstants.KEY_ENABLED;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@SuppressWarnings({"unused", "CanBeFinal"})
@PrepareForTest({
        MobileCenter.class,
        MobileCenter.UncaughtExceptionHandler.class,
        DefaultChannel.class,
        Constants.class,
        MobileCenterLog.class,
        StartServiceLog.class,
        StorageHelper.class,
        StorageHelper.PreferencesStorage.class,
        IdHelper.class,
        StorageHelper.DatabaseStorage.class,
        DeviceInfoHelper.class,
        Thread.class,
        ShutdownHelper.class,
        CustomProperties.class
})
public class MobileCenterTest {

    private static final String DUMMY_APP_SECRET = "123e4567-e89b-12d3-a456-426655440000";

    @Rule
    public PowerMockRule mPowerMockRule = new PowerMockRule();

    @Mock
    private Iterator<ContentValues> mDataBaseScannerIterator;

    @Mock
    private DefaultChannel mChannel;

    @Mock
    private StartServiceLog mStartServiceLog;

    private Application application;

    @Before
    public void setUp() throws Exception {
        MobileCenter.unsetInstance();
        DummyService.sharedInstance = null;
        AnotherDummyService.sharedInstance = null;

        mChannel = mock(DefaultChannel.class);
        whenNew(DefaultChannel.class).withAnyArguments().thenReturn(mChannel);

        mStartServiceLog = mock(StartServiceLog.class);
        whenNew(StartServiceLog.class).withAnyArguments().thenReturn(mStartServiceLog);

        application = mock(Application.class);
        when(application.getApplicationContext()).thenReturn(application);

        mockStatic(Constants.class);
        mockStatic(MobileCenterLog.class);
        mockStatic(StorageHelper.class);
        mockStatic(StorageHelper.PreferencesStorage.class);
        mockStatic(IdHelper.class);
        mockStatic(StorageHelper.DatabaseStorage.class);
        mockStatic(Thread.class);
        mockStatic(ShutdownHelper.class);
        mockStatic(DeviceInfoHelper.class);

        /* First call to com.microsoft.azure.mobile.MobileCenter.isEnabled shall return true, initial state. */
        when(StorageHelper.PreferencesStorage.getBoolean(anyString(), eq(true))).thenReturn(true);

        /* Then simulate further changes to state. */
        PowerMockito.doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {

                /* Whenever the new state is persisted, make further calls return the new state. */
                String key = (String) invocation.getArguments()[0];
                boolean enabled = (Boolean) invocation.getArguments()[1];
                when(StorageHelper.PreferencesStorage.getBoolean(key, true)).thenReturn(enabled);
                return null;
            }
        }).when(StorageHelper.PreferencesStorage.class);
        StorageHelper.PreferencesStorage.putBoolean(anyString(), anyBoolean());

        /* Mock empty database. */
        StorageHelper.DatabaseStorage databaseStorage = mock(StorageHelper.DatabaseStorage.class);
        when(StorageHelper.DatabaseStorage.getDatabaseStorage(anyString(), anyString(), anyInt(), any(ContentValues.class), anyInt(), any(StorageHelper.DatabaseStorage.DatabaseErrorListener.class))).thenReturn(databaseStorage);
        StorageHelper.DatabaseStorage.DatabaseScanner databaseScanner = mock(StorageHelper.DatabaseStorage.DatabaseScanner.class);
        when(databaseStorage.getScanner(anyString(), anyObject())).thenReturn(databaseScanner);
        when(databaseScanner.iterator()).thenReturn(mDataBaseScannerIterator);
    }

    @After
    public void tearDown() {
        Constants.APPLICATION_DEBUGGABLE = false;
    }

    @Test
    public void singleton() {
        assertNotNull(MobileCenter.getInstance());
        assertSame(MobileCenter.getInstance(), MobileCenter.getInstance());
    }

    @Test
    public void nullVarargClass() {
        MobileCenter.start(application, DUMMY_APP_SECRET, (Class<? extends MobileCenterService>) null);

        /* Verify that no services have been auto-loaded since none are configured for this */
        assertTrue(MobileCenter.isConfigured());
        assertEquals(0, MobileCenter.getInstance().getServices().size());
        assertEquals(application, MobileCenter.getInstance().getApplication());
    }

    @Test
    public void nullVarargArray() {
        //noinspection ConfusingArgumentToVarargsMethod
        MobileCenter.start(application, DUMMY_APP_SECRET, (Class<? extends MobileCenterService>[]) null);
        MobileCenter.start((Class<? extends MobileCenterService>) null);
        //noinspection ConfusingArgumentToVarargsMethod
        MobileCenter.start((Class<? extends MobileCenterService>[]) null);

        /* Verify that no services have been auto-loaded since none are configured for this */
        assertTrue(MobileCenter.isConfigured());
        assertEquals(0, MobileCenter.getInstance().getServices().size());
        assertEquals(application, MobileCenter.getInstance().getApplication());
    }

    @Test
    public void startServiceBeforeConfigure() {
        MobileCenter.start(DummyService.class);
        assertFalse(MobileCenter.isConfigured());
        assertNull(MobileCenter.getInstance().getServices());
    }

    @Test
    public void useDummyServiceTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);

        /* Verify that single service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
        verify(mChannel).enqueue(eq(mStartServiceLog), eq(MobileCenter.CORE_GROUP));
        List<String> services = new ArrayList<>();
        services.add(service.getServiceName());
        verify(mStartServiceLog).setServices(eq(services));
    }

    @Test
    public void useDummyServiceTestSplitCall() {
        assertFalse(MobileCenter.isConfigured());
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        assertTrue(MobileCenter.isConfigured());
        MobileCenter.start(DummyService.class);

        /* Verify that single service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
        verify(mChannel).enqueue(eq(mStartServiceLog), eq(MobileCenter.CORE_GROUP));
        List<String> services = new ArrayList<>();
        services.add(service.getServiceName());
        verify(mStartServiceLog).setServices(eq(services));
    }

    @Test
    public void configureAndStartTwiceTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        MobileCenter.start(application, DUMMY_APP_SECRET + "a", AnotherDummyService.class); //ignored

        /* Verify that single service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
        verify(service, never()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET + "a"), any(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
        verify(mChannel).enqueue(eq(mStartServiceLog), eq(MobileCenter.CORE_GROUP));
        List<String> services = new ArrayList<>();
        services.add(service.getServiceName());
        verify(mStartServiceLog).setServices(eq(services));
    }

    @Test
    public void configureTwiceTest() {
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.configure(application, DUMMY_APP_SECRET + "a"); //ignored
        MobileCenter.start(DummyService.class);

        /* Verify that single service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
        verify(service, never()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET + "a"), any(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
        verify(mChannel).enqueue(eq(mStartServiceLog), eq(MobileCenter.CORE_GROUP));
        List<String> services = new ArrayList<>();
        services.add(service.getServiceName());
        verify(mStartServiceLog).setServices(eq(services));
    }

    @Test
    public void startTwoServicesTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, AnotherDummyService.class);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
        verify(mChannel).enqueue(eq(mStartServiceLog), eq(MobileCenter.CORE_GROUP));
        List<String> services = new ArrayList<>();
        services.add(DummyService.getInstance().getServiceName());
        services.add(AnotherDummyService.getInstance().getServiceName());
        verify(mStartServiceLog).setServices(eq(services));
    }

    @Test
    public void startTwoServicesSplit() {
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.start(DummyService.class, AnotherDummyService.class);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
        verify(mChannel).enqueue(eq(mStartServiceLog), eq(MobileCenter.CORE_GROUP));
        List<String> services = new ArrayList<>();
        services.add(DummyService.getInstance().getServiceName());
        services.add(AnotherDummyService.getInstance().getServiceName());
        verify(mStartServiceLog).setServices(eq(services));
    }

    @Test
    public void startTwoServicesSplitEvenMore() {
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.start(DummyService.class);
        MobileCenter.start(AnotherDummyService.class);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
        verify(mChannel, times(2)).enqueue(any(StartServiceLog.class), eq(MobileCenter.CORE_GROUP));
        List<String> services1 = new ArrayList<>();
        services1.add(DummyService.getInstance().getServiceName());
        verify(mStartServiceLog).setServices(eq(services1));
        List<String> services2 = new ArrayList<>();
        services2.add(DummyService.getInstance().getServiceName());
        verify(mStartServiceLog).setServices(eq(services2));
    }

    @Test
    public void startTwoServicesWithSomeInvalidReferences() {
        MobileCenter.start(application, DUMMY_APP_SECRET, null, DummyService.class, null, InvalidService.class, AnotherDummyService.class, null);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
        verify(mChannel).enqueue(eq(mStartServiceLog), eq(MobileCenter.CORE_GROUP));
        List<String> services = new ArrayList<>();
        services.add(DummyService.getInstance().getServiceName());
        services.add(AnotherDummyService.getInstance().getServiceName());
        verify(mStartServiceLog).setServices(eq(services));
    }

    @Test
    public void startTwoServicesWithSomeInvalidReferencesSplit() {
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.start(null, DummyService.class, null);
        MobileCenter.start(InvalidService.class, AnotherDummyService.class, null);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
        verify(mChannel, times(2)).enqueue(any(StartServiceLog.class), eq(MobileCenter.CORE_GROUP));
        List<String> services1 = new ArrayList<>();
        services1.add(DummyService.getInstance().getServiceName());
        verify(mStartServiceLog).setServices(eq(services1));
        List<String> services2 = new ArrayList<>();
        services2.add(DummyService.getInstance().getServiceName());
        verify(mStartServiceLog).setServices(eq(services2));
    }

    @Test
    public void startServiceTwice() {

        /* Start once. */
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.start(DummyService.class);

        /* Check. */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);

        /* Start twice, this call is ignored. */
        MobileCenter.start(DummyService.class);

        /* Verify that single service has been loaded and configured (only once interaction). */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        verify(service).getLogFactories();
        verify(service).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
        verify(mChannel).enqueue(eq(mStartServiceLog), eq(MobileCenter.CORE_GROUP));
        List<String> services = new ArrayList<>();
        services.add(DummyService.getInstance().getServiceName());
        verify(mStartServiceLog).setServices(eq(services));
    }

    @Test
    public void enableTest() throws Exception {

        /* Mock handler for asynchronous persistence */
        HandlerThread mockHandlerThread = PowerMockito.mock(HandlerThread.class);
        Looper mockLooper = PowerMockito.mock(Looper.class);
        whenNew(HandlerThread.class).withArguments(THREAD_NAME).thenReturn(mockHandlerThread);
        when(mockHandlerThread.getLooper()).thenReturn(mockLooper);
        Handler mockPersistenceHandler = PowerMockito.mock(Handler.class);
        whenNew(Handler.class).withArguments(mockLooper).thenReturn(mockPersistenceHandler);
        when(mockPersistenceHandler.post(any(Runnable.class))).then(new Answer<Boolean>() {

            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                ((Runnable) invocation.getArguments()[0]).run();
                return true;
            }
        });

        /* Start MobileCenter SDK */
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, AnotherDummyService.class);
        MobileCenter mobileCenter = MobileCenter.getInstance();

        /* Verify services are enabled by default */
        Set<MobileCenterService> services = mobileCenter.getServices();
        assertTrue(MobileCenter.isEnabled());
        DummyService dummyService = DummyService.getInstance();
        AnotherDummyService anotherDummyService = AnotherDummyService.getInstance();
        for (MobileCenterService service : services) {
            assertTrue(service.isInstanceEnabled());
        }

        /* Explicit set enabled should not change that */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertTrue(service.isInstanceEnabled());
        }
        verify(dummyService, never()).setInstanceEnabled(anyBoolean());
        verify(anotherDummyService, never()).setInstanceEnabled(anyBoolean());
        verify(mChannel, times(2)).setEnabled(true);

        /* Verify disabling base disables all services */
        MobileCenter.setEnabled(false);
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertFalse(service.isInstanceEnabled());
        }
        verify(dummyService).setInstanceEnabled(false);
        verify(anotherDummyService).setInstanceEnabled(false);
        verify(application).unregisterActivityLifecycleCallbacks(dummyService);
        verify(application).unregisterActivityLifecycleCallbacks(anotherDummyService);
        verify(mChannel).setEnabled(false);

        /* Verify re-enabling base re-enables all services */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertTrue(service.isInstanceEnabled());
        }
        verify(dummyService).setInstanceEnabled(true);
        verify(anotherDummyService).setInstanceEnabled(true);
        verify(application, times(2)).registerActivityLifecycleCallbacks(dummyService);
        verify(application, times(2)).registerActivityLifecycleCallbacks(anotherDummyService);
        verify(mChannel, times(3)).setEnabled(true);

        /* Verify that disabling one service leaves base and other services enabled */
        dummyService.setInstanceEnabled(false);
        assertFalse(dummyService.isInstanceEnabled());
        assertTrue(MobileCenter.isEnabled());
        assertTrue(anotherDummyService.isInstanceEnabled());

        /* Enable back via main class. */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertTrue(service.isInstanceEnabled());
        }
        verify(dummyService, times(2)).setInstanceEnabled(true);
        verify(anotherDummyService).setInstanceEnabled(true);
        verify(mChannel, times(4)).setEnabled(true);

        /* Enable service after the SDK is disabled. */
        MobileCenter.setEnabled(false);
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertFalse(service.isInstanceEnabled());
        }
        dummyService.setInstanceEnabled(true);
        assertFalse(dummyService.isInstanceEnabled());
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
        assertFalse(MobileCenter.isEnabled());
        verify(mChannel, times(2)).setEnabled(false);

        /* Disable back via main class. */
        MobileCenter.setEnabled(false);
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertFalse(service.isInstanceEnabled());
        }
        verify(mChannel, times(3)).setEnabled(false);

        /* Check factories / channel only once interactions. */
        verify(dummyService).getLogFactories();
        verify(dummyService).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
        verify(anotherDummyService).getLogFactories();
        verify(anotherDummyService).onStarted(any(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
    }

    @Test
    public void enableBeforeConfiguredTest() {
        /* Test isEnabled and setEnabled before configure */
        assertFalse(MobileCenter.isEnabled());
        MobileCenter.setEnabled(true);
        assertFalse(MobileCenter.isEnabled());
        PowerMockito.verifyStatic(times(3));
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
    }

    @Test
    public void disablePersisted() {
        when(StorageHelper.PreferencesStorage.getBoolean(KEY_ENABLED, true)).thenReturn(false);
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, AnotherDummyService.class);
        MobileCenter mobileCenter = MobileCenter.getInstance();

        /* Verify services are enabled by default but MobileCenter is disabled. */
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : mobileCenter.getServices()) {
            assertTrue(service.isInstanceEnabled());
            verify(application, never()).registerActivityLifecycleCallbacks(service);
        }

        /* Verify we can enable back. */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : mobileCenter.getServices()) {
            assertTrue(service.isInstanceEnabled());
            verify(application).registerActivityLifecycleCallbacks(service);
            verify(application, never()).unregisterActivityLifecycleCallbacks(service);
        }
    }

    @Test
    public void disablePersistedAndDisable() {
        when(StorageHelper.PreferencesStorage.getBoolean(KEY_ENABLED, true)).thenReturn(false);
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, AnotherDummyService.class);
        MobileCenter mobileCenter = MobileCenter.getInstance();

        /* Its already disabled so disable should have no effect on MobileCenter but should disable services. */
        MobileCenter.setEnabled(false);
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : mobileCenter.getServices()) {
            assertFalse(service.isInstanceEnabled());
            verify(application, never()).registerActivityLifecycleCallbacks(service);
            verify(application, never()).unregisterActivityLifecycleCallbacks(service);
        }

        /* Verify we can enable MobileCenter back, should have no effect on service except registering the application life cycle callbacks. */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : mobileCenter.getServices()) {
            assertTrue(service.isInstanceEnabled());
            verify(application).registerActivityLifecycleCallbacks(service);
            verify(application, never()).unregisterActivityLifecycleCallbacks(service);
        }
    }

    @Test
    public void invalidServiceTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, InvalidService.class);
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString(), any(NoSuchMethodException.class));
    }

    @Test
    public void nullApplicationTest() {
        MobileCenter.start(null, DUMMY_APP_SECRET, DummyService.class);
        verify(DummyService.getInstance(), never()).onStarted(any(Context.class), anyString(), any(Channel.class));
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
    }

    @Test
    public void nullAppIdentifierTest() {
        MobileCenter.start(application, null, DummyService.class);
        verify(DummyService.getInstance(), never()).onStarted(any(Context.class), anyString(), any(Channel.class));
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
    }

    @Test
    public void emptyAppIdentifierTest() {
        MobileCenter.start(application, "", DummyService.class);
        verify(DummyService.getInstance(), never()).onStarted(any(Context.class), anyString(), any(Channel.class));
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
    }

    @Test
    public void duplicateServiceTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, DummyService.class);

        /* Verify that only one service has been loaded and configured */
        verify(DummyService.getInstance()).onStarted(notNull(Context.class), eq(DUMMY_APP_SECRET), any(Channel.class));
        assertEquals(1, MobileCenter.getInstance().getServices().size());
    }

    @Test
    public void getInstallIdBeforeStart() {
        assertNull(MobileCenter.getInstallId());
    }

    @Test
    public void setWrapperSdkTest() throws Exception {

        /* Call method. */
        WrapperSdk wrapperSdk = new WrapperSdk();
        MobileCenter.setWrapperSdk(wrapperSdk);

        /* Check propagation. */
        verifyStatic();
        DeviceInfoHelper.setWrapperSdk(wrapperSdk);

        /* Since the channel was not created when setting wrapper, no need to refresh channel after start. */
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verify(mChannel, never()).invalidateDeviceCache();

        /* Update wrapper SDK and check channel refreshed. */
        wrapperSdk = new WrapperSdk();
        MobileCenter.setWrapperSdk(wrapperSdk);
        verify(mChannel).invalidateDeviceCache();
    }


    @Test
    public void setDefaultLogLevelRelease() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verifyStatic(never());
        MobileCenterLog.setLogLevel(anyInt());
    }

    @Test
    public void setDefaultLogLevelDebug() {
        Constants.APPLICATION_DEBUGGABLE = true;
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verifyStatic();
        MobileCenterLog.setLogLevel(android.util.Log.WARN);
    }

    @Test
    public void dontSetDefaultLogLevel() {
        Constants.APPLICATION_DEBUGGABLE = true;
        MobileCenter.setLogLevel(android.util.Log.VERBOSE);
        verifyStatic();
        MobileCenterLog.setLogLevel(android.util.Log.VERBOSE);
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verifyStatic(never());
        MobileCenterLog.setLogLevel(android.util.Log.WARN);
    }

    @Test
    public void setLogUrl() throws Exception {

        /* Change log URL before start. */
        String logUrl = "http://mock";
        MobileCenter.setLogUrl(logUrl);

        /* No effect for now. */
        verify(mChannel, never()).setLogUrl(logUrl);

        /* Start should propagate the log URL. */
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verify(mChannel).setLogUrl(logUrl);

        /* Change it after, should work immediately. */
        logUrl = "http://mock2";
        MobileCenter.setLogUrl(logUrl);
        verify(mChannel).setLogUrl(logUrl);
    }

    @Test
    public void setCustomPropertiesTest() throws Exception {
        CustomPropertiesLog log = mock(CustomPropertiesLog.class);
        whenNew(CustomPropertiesLog.class).withAnyArguments().thenReturn(log);
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);

        /* Set null. */
        MobileCenter.setCustomProperties(null);
        verify(mChannel, never()).enqueue(eq(log), eq(MobileCenter.CORE_GROUP));
        verifyStatic(times(1));
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());

        /* Set empty. */
        CustomProperties empty = new CustomProperties();
        MobileCenter.setCustomProperties(empty);
        verify(mChannel, never()).enqueue(eq(log), eq(MobileCenter.CORE_GROUP));
        verifyStatic(times(2));
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());

        /* Set normal. */
        CustomProperties properties = new CustomProperties();
        properties.set("test", "test");
        MobileCenter.setCustomProperties(properties);
        verify(log).setProperties(eq(properties.getProperties()));
        verify(mChannel).enqueue(eq(log), eq(MobileCenter.CORE_GROUP));
    }

    @Test
    public void uncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        when(Thread.getDefaultUncaughtExceptionHandler()).thenReturn(defaultUncaughtExceptionHandler);
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.UncaughtExceptionHandler handler = MobileCenter.getInstance().getUncaughtExceptionHandler();
        assertNotNull(handler);
        assertEquals(defaultUncaughtExceptionHandler, handler.getDefaultUncaughtExceptionHandler());
        verifyStatic();
        Thread.setDefaultUncaughtExceptionHandler(eq(handler));

        Thread thread = mock(Thread.class);
        Throwable exception = mock(Throwable.class);
        handler.uncaughtException(thread, exception);
        verify(mChannel).shutdown();
        verify(defaultUncaughtExceptionHandler).uncaughtException(eq(thread), eq(exception));

        MobileCenter.setEnabled(false);
        verifyStatic();
        Thread.setDefaultUncaughtExceptionHandler(eq(defaultUncaughtExceptionHandler));
        handler.uncaughtException(thread, exception);
        verify(mChannel, times(1)).shutdown();

        when(Thread.getDefaultUncaughtExceptionHandler()).thenReturn(null);
        MobileCenter.setEnabled(true);
        MobileCenter.getInstance().setChannel(null);
        assertNull(handler.getDefaultUncaughtExceptionHandler());
        handler.uncaughtException(thread, exception);
        verifyStatic();
        ShutdownHelper.shutdown(10);
    }

    private static class DummyService extends AbstractMobileCenterService {

        private static DummyService sharedInstance;

        @SuppressWarnings("WeakerAccess")
        public static DummyService getInstance() {
            if (sharedInstance == null) {
                sharedInstance = spy(new DummyService());
            }
            return sharedInstance;
        }

        @Override
        protected String getGroupName() {
            return "group_dummy";
        }

        @Override
        public String getServiceName() {
            return "Dummy";
        }

        @Override
        protected String getLoggerTag() {
            return "DummyLog";
        }
    }

    private static class AnotherDummyService extends AbstractMobileCenterService {

        private static AnotherDummyService sharedInstance;

        @SuppressWarnings("WeakerAccess")
        public static AnotherDummyService getInstance() {
            if (sharedInstance == null) {
                sharedInstance = spy(new AnotherDummyService());
            }
            return sharedInstance;
        }

        @Override
        public Map<String, LogFactory> getLogFactories() {
            HashMap<String, LogFactory> logFactories = new HashMap<>();
            logFactories.put("mock", mock(LogFactory.class));
            return logFactories;
        }

        @Override
        protected String getGroupName() {
            return "group_another_dummy";
        }

        @Override
        public String getServiceName() {
            return "AnotherDummy";
        }

        @Override
        protected String getLoggerTag() {
            return "AnotherDummyLog";
        }
    }

    private static class InvalidService extends AbstractMobileCenterService {

        @Override
        protected String getGroupName() {
            return "group_invalid";
        }

        @Override
        public String getServiceName() {
            return "Invalid";
        }

        @Override
        protected String getLoggerTag() {
            return "InvalidLog";
        }
    }
}
