package flutter.plugins.contactsservice.contactsservice;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import static android.app.Activity.RESULT_CANCELED;
import static android.provider.ContactsContract.CommonDataKinds;
import static android.provider.ContactsContract.CommonDataKinds.Email;
import static android.provider.ContactsContract.CommonDataKinds.Organization;
import static android.provider.ContactsContract.CommonDataKinds.Phone;
import static android.provider.ContactsContract.CommonDataKinds.StructuredName;
import static android.provider.ContactsContract.CommonDataKinds.StructuredPostal;

public class ContactsServicePlugin implements MethodCallHandler, FlutterPlugin, ActivityAware {

    private static final int FORM_OPERATION_CANCELED = 1;
    private static final int FORM_COULD_NOT_BE_OPEN = 2;

    private static final String LOG_TAG = "flutter_contacts";
    private ContentResolver contentResolver;
    private MethodChannel methodChannel;
    private BaseContactsServiceDelegate delegate;
    private Resources resources;

    /**
     * Recreated on every engine attachment: {@code onDetachedFromEngine} shuts it down, and the
     * same plugin instance can be attached to a new engine afterwards.
     */
    private ExecutorService executor;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(0, 10, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1000));
    }

    /**
     * Submits background work, reporting the failure through {@code result} rather than throwing if
     * the executor is gone or saturated.
     */
    private boolean submit(Runnable work, Result result) {
        final ExecutorService current = this.executor;
        if (current == null) {
            result.error("contactsUnavailable", "Plugin is detached from the engine", null);
            return false;
        }
        try {
            current.execute(work);
            return true;
        } catch (RejectedExecutionException e) {
            result.error("contactsUnavailable", "Too many pending contact requests", null);
            return false;
        }
    }

    /**
     * Delivers a {@link Result} back on the main thread, which is the only thread a
     * {@link MethodChannel.Result} may be invoked from.
     *
     * <p>The result is dropped if the plugin has been detached from the engine in the meantime -
     * calling into a torn-down engine is what used to crash the app.
     */
    private void postResult(final Runnable delivery) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (methodChannel == null) {
                    // Detached from the engine while the background work was in flight.
                    return;
                }
                delivery.run();
            }
        });
    }

    private void initInstance(BinaryMessenger messenger, Context context) {
        methodChannel = new MethodChannel(messenger, "github.com/clovisnicolas/flutter_contacts");
        methodChannel.setMethodCallHandler(this);
        this.contentResolver = context.getContentResolver();
    }

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        resources = binding.getApplicationContext().getResources();
        executor = newExecutor();
        initInstance(binding.getBinaryMessenger(), binding.getApplicationContext());
        this.delegate = new ContactServiceDelegate(binding.getApplicationContext());
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        if (methodChannel != null) {
            methodChannel.setMethodCallHandler(null);
        }
        methodChannel = null;
        contentResolver = null;
        this.delegate = null;
        resources = null;
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        switch (call.method) {
            case "getContacts": {
                this.getContacts(call.method, (String) call.argument("query"), (boolean) call.argument("withThumbnails"), (boolean) call.argument("photoHighResolution"), (boolean) call.argument("orderByGivenName"), (boolean) call.argument("androidLocalizedLabels"), result);
                break;
            }
            case "getContactsForPhone": {
                this.getContactsForPhone(call.method, (String) call.argument("phone"), (boolean) call.argument("withThumbnails"), (boolean) call.argument("photoHighResolution"), (boolean) call.argument("orderByGivenName"), (boolean) call.argument("androidLocalizedLabels"), result);
                break;
            }
            case "getContactsForEmail": {
                this.getContactsForEmail(call.method, (String) call.argument("email"), (boolean) call.argument("withThumbnails"), (boolean) call.argument("photoHighResolution"), (boolean) call.argument("orderByGivenName"), (boolean) call.argument("androidLocalizedLabels"), result);
                break;
            }
            case "getAvatar": {
                final Contact contact = Contact.fromMap((HashMap) call.argument("contact"));
                this.getAvatar(contact, (boolean) call.argument("photoHighResolution"), result);
                break;
            }
            case "addContact": {
                final Contact contact = Contact.fromMap((HashMap) call.arguments);
                if (this.addContact(contact)) {
                    result.success(null);
                } else {
                    result.error(null, "Failed to add the contact", null);
                }
                break;
            }
            case "deleteContact": {
                final Contact contact = Contact.fromMap((HashMap) call.arguments);
                if (this.deleteContact(contact)) {
                    result.success(null);
                } else {
                    result.error(null, "Failed to delete the contact, make sure it has a valid identifier", null);
                }
                break;
            }
            case "updateContact": {
                final Contact contact = Contact.fromMap((HashMap) call.arguments);
                if (this.updateContact(contact)) {
                    result.success(null);
                } else {
                    result.error(null, "Failed to update the contact, make sure it has a valid identifier", null);
                }
                break;
            }
            case "openExistingContact": {
                final Contact contact = Contact.fromMap((HashMap) call.argument("contact"));
                final boolean localizedLabels = call.argument("androidLocalizedLabels");
                if (delegate != null) {
                    delegate.setResult(result);
                    delegate.setLocalizedLabels(localizedLabels);
                    delegate.openExistingContact(contact);
                } else {
                    result.success(FORM_COULD_NOT_BE_OPEN);
                }
                break;
            }
            case "openContactForm": {
                final boolean localizedLabels = call.argument("androidLocalizedLabels");
                if (delegate != null) {
                    delegate.setResult(result);
                    delegate.setLocalizedLabels(localizedLabels);
                    delegate.openContactForm();
                } else {
                    result.success(FORM_COULD_NOT_BE_OPEN);
                }
                break;
            }
            case "openDeviceContactPicker": {
                final boolean localizedLabels = call.argument("androidLocalizedLabels");
                openDeviceContactPicker(result, localizedLabels);
                break;
            }
            default: {
                result.notImplemented();
                break;
            }
        }
    }

    private static final String[] PROJECTION =
            {
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Profile.DISPLAY_NAME,
                    ContactsContract.Contacts.Data.MIMETYPE,
                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ContactsContract.RawContacts.ACCOUNT_NAME,
                    StructuredName.DISPLAY_NAME,
                    StructuredName.GIVEN_NAME,
                    StructuredName.MIDDLE_NAME,
                    StructuredName.FAMILY_NAME,
                    StructuredName.PREFIX,
                    StructuredName.SUFFIX,
                    CommonDataKinds.Note.NOTE,
                    Phone.NUMBER,
                    Phone.TYPE,
                    Phone.LABEL,
                    Email.DATA,
                    Email.ADDRESS,
                    Email.TYPE,
                    Email.LABEL,
                    Organization.COMPANY,
                    Organization.TITLE,
                    StructuredPostal.FORMATTED_ADDRESS,
                    StructuredPostal.TYPE,
                    StructuredPostal.LABEL,
                    StructuredPostal.STREET,
                    StructuredPostal.POBOX,
                    StructuredPostal.NEIGHBORHOOD,
                    StructuredPostal.CITY,
                    StructuredPostal.REGION,
                    StructuredPostal.POSTCODE,
                    StructuredPostal.COUNTRY,
            };


    private void getContacts(String callMethod, String query, boolean withThumbnails, boolean photoHighResolution, boolean orderByGivenName, boolean localizedLabels, Result result) {
        submitGetContacts(callMethod, query, result, withThumbnails, photoHighResolution, orderByGivenName, localizedLabels);
    }

    private void getContactsForPhone(String callMethod, String phone, boolean withThumbnails, boolean photoHighResolution, boolean orderByGivenName, boolean localizedLabels, Result result) {
        submitGetContacts(callMethod, phone, result, withThumbnails, photoHighResolution, orderByGivenName, localizedLabels);
    }

    private void getContactsForEmail(String callMethod, String email, boolean withThumbnails, boolean photoHighResolution, boolean orderByGivenName, boolean localizedLabels, Result result) {
        submitGetContacts(callMethod, email, result, withThumbnails, photoHighResolution, orderByGivenName, localizedLabels);
    }

    /**
     * Snapshots everything the background job needs off the main thread and hands it to the
     * executor.
     *
     * <p>The {@link ContentResolver} and {@link Resources} are captured here, on the main thread,
     * rather than read from the plugin's fields inside the worker: {@code onDetachedFromEngine}
     * nulls those fields, and a job already in flight would otherwise dereference null halfway
     * through a contact list.
     */
    private void submitGetContacts(final String callMethod, final String queryArgument, final Result result,
                                   final boolean withThumbnails, final boolean photoHighResolution,
                                   final boolean orderByGivenName, final boolean localizedLabels) {
        final ContentResolver resolver = this.contentResolver;
        final Resources res = this.resources;
        if (resolver == null) {
            result.error("contactsUnavailable", "Plugin is detached from the engine", null);
            return;
        }
        submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final ArrayList<HashMap> contactMaps = loadContacts(callMethod, queryArgument, resolver, res,
                            withThumbnails, photoHighResolution, orderByGivenName, localizedLabels);
                    postResult(new Runnable() {
                        @Override
                        public void run() {
                            if (contactMaps == null) {
                                result.notImplemented();
                            } else {
                                result.success(contactMaps);
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.e(LOG_TAG, "Failed to load contacts for " + callMethod, e);
                    postResult(new Runnable() {
                        @Override
                        public void run() {
                            result.error(callMethod, String.valueOf(e.getMessage()), null);
                        }
                    });
                }
            }
        }, result);
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        if (delegate instanceof ContactServiceDelegate) {
            ((ContactServiceDelegate) delegate).bindToActivity(binding);
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        if (delegate instanceof ContactServiceDelegate) {
            ((ContactServiceDelegate) delegate).unbindActivity();
        }
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
        if (delegate instanceof ContactServiceDelegate) {
            ((ContactServiceDelegate) delegate).bindToActivity(binding);
        }
    }

    @Override
    public void onDetachedFromActivity() {
        if (delegate instanceof ContactServiceDelegate) {
            ((ContactServiceDelegate) delegate).unbindActivity();
        }
    }

    private class BaseContactsServiceDelegate implements PluginRegistry.ActivityResultListener {
        private static final int REQUEST_OPEN_CONTACT_FORM = 52941;
        private static final int REQUEST_OPEN_EXISTING_CONTACT = 52942;
        private static final int REQUEST_OPEN_CONTACT_PICKER = 52943;
        private Result result;
        private boolean localizedLabels;

        void setResult(Result result) {
            this.result = result;
        }

        void setLocalizedLabels(boolean localizedLabels) {
            this.localizedLabels = localizedLabels;
        }

        void finishWithResult(Object result) {
            if (this.result != null) {
                this.result.success(result);
                this.result = null;
            }
        }

        @Override
        public boolean onActivityResult(int requestCode, int resultCode, Intent intent) {
            if (requestCode == REQUEST_OPEN_EXISTING_CONTACT || requestCode == REQUEST_OPEN_CONTACT_FORM) {
                try {
                    Uri ur = intent.getData();
                    finishWithResult(getContactByIdentifier(ur.getLastPathSegment()));
                } catch (NullPointerException e) {
                    finishWithResult(FORM_OPERATION_CANCELED);
                }
                return true;
            }

            if (requestCode == REQUEST_OPEN_CONTACT_PICKER) {
                if (resultCode == RESULT_CANCELED) {
                    finishWithResult(FORM_OPERATION_CANCELED);
                    return true;
                }
                // The picker can return a null Intent even for a non-cancelled result, so the null
                // check has to come before getData() rather than after it.
                final Uri contactUri = intent == null ? null : intent.getData();
                final ContentResolver resolver = contentResolver;
                if (contactUri == null || resolver == null) {
                    finishWithResult(FORM_OPERATION_CANCELED);
                    return true;
                }

                Cursor cursor = null;
                try {
                    cursor = resolver.query(contactUri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        String id = contactUri.getLastPathSegment();
                        getContacts("openDeviceContactPicker", id, false, false, false, localizedLabels, this.result);
                        // getContacts owns the Result from here on.
                        this.result = null;
                    } else {
                        Log.e(LOG_TAG, "onActivityResult - could not read the picked contact");
                        finishWithResult(FORM_OPERATION_CANCELED);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "onActivityResult - failed to read the picked contact", e);
                    finishWithResult(FORM_OPERATION_CANCELED);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                return true;
            }

            finishWithResult(FORM_COULD_NOT_BE_OPEN);
            return false;
        }

        void openExistingContact(Contact contact) {
            String identifier = contact.identifier;
            try {
                HashMap contactMapFromDevice = getContactByIdentifier(identifier);
                // Contact existence check
                if (contactMapFromDevice != null) {
                    Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, identifier);
                    Intent intent = new Intent(Intent.ACTION_EDIT);
                    intent.setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE);
                    intent.putExtra("finishActivityOnSaveCompleted", true);
                    startIntent(intent, REQUEST_OPEN_EXISTING_CONTACT);
                } else {
                    finishWithResult(FORM_COULD_NOT_BE_OPEN);
                }
            } catch (Exception e) {
                finishWithResult(FORM_COULD_NOT_BE_OPEN);
            }
        }

        void openContactForm() {
            try {
                Intent intent = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
                intent.putExtra("finishActivityOnSaveCompleted", true);
                startIntent(intent, REQUEST_OPEN_CONTACT_FORM);
            } catch (Exception e) {
            }
        }

        void openContactPicker() {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
            startIntent(intent, REQUEST_OPEN_CONTACT_PICKER);
        }

        void startIntent(Intent intent, int request) {
        }

        HashMap getContactByIdentifier(String identifier) {
            final ContentResolver resolver = contentResolver;
            if (resolver == null) {
                return null;
            }
            ArrayList<Contact> matchingContacts;
            {
                Cursor cursor = resolver.query(
                        ContactsContract.Data.CONTENT_URI, PROJECTION,
                        ContactsContract.RawContacts.CONTACT_ID + " = ?",
                        new String[]{identifier},
                        null
                );
                try {
                    matchingContacts = getContactsFrom(cursor, resources, localizedLabels);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
            if (matchingContacts.size() > 0) {
                return matchingContacts.iterator().next().toMap();
            }
            return null;
        }
    }

    private void openDeviceContactPicker(Result result, boolean localizedLabels) {
        if (delegate != null) {
            delegate.setResult(result);
            delegate.setLocalizedLabels(localizedLabels);
            delegate.openContactPicker();
        } else {
            result.success(FORM_COULD_NOT_BE_OPEN);
        }
    }

    private class ContactServiceDelegate extends BaseContactsServiceDelegate {
        private final Context context;
        private ActivityPluginBinding activityPluginBinding;

        ContactServiceDelegate(Context context) {
            this.context = context;
        }

        void bindToActivity(ActivityPluginBinding activityPluginBinding) {
            this.activityPluginBinding = activityPluginBinding;
            this.activityPluginBinding.addActivityResultListener(this);
        }

        void unbindActivity() {
            this.activityPluginBinding.removeActivityResultListener(this);
            this.activityPluginBinding = null;
        }

        @Override
        void startIntent(Intent intent, int request) {
            if (this.activityPluginBinding != null) {
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    activityPluginBinding.getActivity().startActivityForResult(intent, request);
                } else {
                    finishWithResult(FORM_COULD_NOT_BE_OPEN);
                }
            } else {
                context.startActivity(intent);
            }
        }
    }

    /**
     * Runs on a background thread. Every resource it touches is passed in, so nothing here can be
     * torn out from under it by {@code onDetachedFromEngine}.
     *
     * @return the contacts as maps, or {@code null} if {@code callMethod} is unknown.
     */
    private ArrayList<HashMap> loadContacts(String callMethod, String queryArgument,
                                            ContentResolver resolver, Resources res,
                                            boolean withThumbnails, boolean photoHighResolution,
                                            boolean orderByGivenName, boolean localizedLabels) {
        ArrayList<Contact> contacts;
        switch (callMethod) {
            case "openDeviceContactPicker":
                contacts = getContactsFrom(getCursor(resolver, null, queryArgument), res, localizedLabels);
                break;
            case "getContacts":
                contacts = getContactsFrom(getCursor(resolver, queryArgument, null), res, localizedLabels);
                break;
            case "getContactsForPhone":
                contacts = getContactsFrom(getCursorForPhone(resolver, queryArgument), res, localizedLabels);
                break;
            case "getContactsForEmail":
                contacts = getContactsFrom(getCursorForEmail(resolver, queryArgument), res, localizedLabels);
                break;
            default:
                return null;
        }

        if (withThumbnails) {
            for (Contact c : contacts) {
                final byte[] avatar = loadContactPhotoHighRes(c.identifier, photoHighResolution, resolver);
                if (avatar != null) {
                    c.avatar = avatar;
                } else {
                    // To stay backwards-compatible, return an empty byte array rather than `null`.
                    c.avatar = new byte[0];
                }
            }
        }

        if (orderByGivenName) {
            Comparator<Contact> compareByGivenName = new Comparator<Contact>() {
                @Override
                public int compare(Contact contactA, Contact contactB) {
                    return contactA.compareTo(contactB);
                }
            };
            Collections.sort(contacts, compareByGivenName);
        }

        //Transform the list of contacts to a list of Map
        ArrayList<HashMap> contactMaps = new ArrayList<>();
        for (Contact c : contacts) {
            contactMaps.add(c.toMap());
        }

        return contactMaps;
    }


    private Cursor getCursor(ContentResolver resolver, String query, String rawContactId) {
        String selection = "(" + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.Data.MIMETYPE + "=? OR "
                + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.Data.MIMETYPE + "=? OR "
                + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.Data.MIMETYPE + "=? OR "
                + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.RawContacts.ACCOUNT_TYPE + "=?" + ")";
        ArrayList<String> selectionArgs = new ArrayList<>(Arrays.asList(CommonDataKinds.Note.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
                Phone.CONTENT_ITEM_TYPE, StructuredName.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
                StructuredPostal.CONTENT_ITEM_TYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.RawContacts.ACCOUNT_TYPE));
        if (query != null) {
            selectionArgs = new ArrayList<>();
            selectionArgs.add(query + "%");
            selection = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?";
        }
        if (rawContactId != null) {
            selectionArgs.add(rawContactId);
            selection += " AND " + ContactsContract.Data.CONTACT_ID + " =?";
        }
        return resolver.query(ContactsContract.Data.CONTENT_URI, PROJECTION, selection, selectionArgs.toArray(new String[selectionArgs.size()]), null);
    }

    private Cursor getCursorForPhone(ContentResolver resolver, String phone) {
        if (phone == null || phone.isEmpty())
            return null;

        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone));
        String[] projection = new String[]{BaseColumns._ID};

        ArrayList<String> contactIds = new ArrayList<>();
        Cursor phoneCursor = resolver.query(uri, projection, null, null, null);
        while (phoneCursor != null && phoneCursor.moveToNext()) {
            contactIds.add(phoneCursor.getString(phoneCursor.getColumnIndex(BaseColumns._ID)));
        }
        if (phoneCursor != null)
            phoneCursor.close();

        if (!contactIds.isEmpty()) {
            String contactIdsListString = contactIds.toString().replace("[", "(").replace("]", ")");
            String contactSelection = ContactsContract.Data.CONTACT_ID + " IN " + contactIdsListString;
            return resolver.query(ContactsContract.Data.CONTENT_URI, PROJECTION, contactSelection, null, null);
        }

        return null;
    }

    private Cursor getCursorForEmail(ContentResolver resolver, String email) {
        if (email == null || email.isEmpty())
            return null;
        ArrayList<String> selectionArgs = new ArrayList<>(Arrays.asList("%" + email + "%"));
        String selection = Email.ADDRESS + " LIKE ?";
        return resolver.query(ContactsContract.Data.CONTENT_URI, PROJECTION, selection, selectionArgs.toArray(new String[selectionArgs.size()]), null);
    }

    /**
     * Builds the list of contacts from the cursor
     *
     * @param cursor
     * @return the list of contacts
     */
    private ArrayList<Contact> getContactsFrom(Cursor cursor, Resources resources, boolean localizedLabels) {
        HashMap<String, Contact> map = new LinkedHashMap<>();

        while (cursor != null && cursor.moveToNext()) {
            int columnIndex = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID);
            String contactId = cursor.getString(columnIndex);

            if (!map.containsKey(contactId)) {
                map.put(contactId, new Contact(contactId));
            }
            Contact contact = map.get(contactId);

            String mimeType = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.MIMETYPE));
            contact.displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
            contact.androidAccountType = cursor.getString(cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE));
            contact.androidAccountName = cursor.getString(cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME));

            //NAMES
            if (mimeType.equals(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)) {
                contact.givenName = cursor.getString(cursor.getColumnIndex(StructuredName.GIVEN_NAME));
                contact.middleName = cursor.getString(cursor.getColumnIndex(StructuredName.MIDDLE_NAME));
                contact.familyName = cursor.getString(cursor.getColumnIndex(StructuredName.FAMILY_NAME));
                contact.prefix = cursor.getString(cursor.getColumnIndex(StructuredName.PREFIX));
                contact.suffix = cursor.getString(cursor.getColumnIndex(StructuredName.SUFFIX));
            }
            // NOTE
            else if (mimeType.equals(CommonDataKinds.Note.CONTENT_ITEM_TYPE)) {
                contact.note = cursor.getString(cursor.getColumnIndex(CommonDataKinds.Note.NOTE));
            }
            //PHONES
            else if (mimeType.equals(CommonDataKinds.Phone.CONTENT_ITEM_TYPE)) {
                String phoneNumber = cursor.getString(cursor.getColumnIndex(Phone.NUMBER));
                if (!TextUtils.isEmpty(phoneNumber)) {
                    int type = cursor.getInt(cursor.getColumnIndex(Phone.TYPE));
                    String label = Item.getPhoneLabel(resources, type, cursor, localizedLabels);
                    contact.phones.add(new Item(label, phoneNumber, type));
                }
            }
            //MAILS
            else if (mimeType.equals(CommonDataKinds.Email.CONTENT_ITEM_TYPE)) {
                String email = cursor.getString(cursor.getColumnIndex(Email.ADDRESS));
                int type = cursor.getInt(cursor.getColumnIndex(Email.TYPE));
                if (!TextUtils.isEmpty(email)) {
                    String label = Item.getEmailLabel(resources, type, cursor, localizedLabels);
                    contact.emails.add(new Item(label, email, type));
                }
            }
            //ORG
            else if (mimeType.equals(CommonDataKinds.Organization.CONTENT_ITEM_TYPE)) {
                contact.company = cursor.getString(cursor.getColumnIndex(Organization.COMPANY));
                contact.jobTitle = cursor.getString(cursor.getColumnIndex(Organization.TITLE));
            }
            //ADDRESSES
            else if (mimeType.equals(CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)) {
                int type = cursor.getInt(cursor.getColumnIndex(StructuredPostal.TYPE));
                String label = PostalAddress.getLabel(resources, type, cursor, localizedLabels);
                String street = cursor.getString(cursor.getColumnIndex(StructuredPostal.STREET));
                String city = cursor.getString(cursor.getColumnIndex(StructuredPostal.CITY));
                String postcode = cursor.getString(cursor.getColumnIndex(StructuredPostal.POSTCODE));
                String region = cursor.getString(cursor.getColumnIndex(StructuredPostal.REGION));
                String country = cursor.getString(cursor.getColumnIndex(StructuredPostal.COUNTRY));
                contact.postalAddresses.add(new PostalAddress(label, street, city, postcode, region, country, type));
            }
            // BIRTHDAY
            else if (mimeType.equals(CommonDataKinds.Event.CONTENT_ITEM_TYPE)) {
                int eventType = cursor.getInt(cursor.getColumnIndex(CommonDataKinds.Event.TYPE));
                if (eventType == CommonDataKinds.Event.TYPE_BIRTHDAY) {
                    contact.birthday = cursor.getString(cursor.getColumnIndex(CommonDataKinds.Event.START_DATE));
                }
            }
        }

        if (cursor != null)
            cursor.close();

        return new ArrayList<>(map.values());
    }

    private void getAvatar(final Contact contact, final boolean highRes,
                           final Result result) {
        final ContentResolver resolver = this.contentResolver;
        if (resolver == null) {
            result.error("contactsUnavailable", "Plugin is detached from the engine", null);
            return;
        }
        submit(new Runnable() {
            @Override
            public void run() {
                final byte[] avatar = loadContactPhotoHighRes(contact.identifier, highRes, resolver);
                postResult(new Runnable() {
                    @Override
                    public void run() {
                        result.success(avatar);
                    }
                });
            }
        }, result);
    }

    /**
     * Reads a contact's photo. Never throws: a contact whose avatar cannot be read is not a reason
     * to fail the whole request, so every failure mode returns {@code null} and the caller
     * substitutes an empty byte array.
     */
    private static byte[] loadContactPhotoHighRes(final String identifier,
                                                  final boolean photoHighResolution, final ContentResolver contentResolver) {
        if (contentResolver == null || identifier == null) return null;
        try {
            final Uri uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, Long.parseLong(identifier));
            final InputStream input = ContactsContract.Contacts.openContactPhotoInputStream(contentResolver, uri, photoHighResolution);

            if (input == null) return null;

            final Bitmap bitmap;
            try {
                bitmap = BitmapFactory.decodeStream(input);
            } finally {
                input.close();
            }

            // decodeStream returns null for a corrupt or unsupported photo blob.
            if (bitmap == null) return null;

            final ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            final byte[] bytes = stream.toByteArray();
            stream.close();
            return bytes;
        } catch (final Exception ex) {
            // Catches NumberFormatException from a non-numeric identifier, SecurityException when
            // the contacts permission is revoked mid-run, and IOException from the stream.
            Log.e(LOG_TAG, "Failed to load photo for contact " + identifier + ": " + ex.getMessage());
            return null;
        }
    }

    private boolean addContact(Contact contact) {

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        ContentProviderOperation.Builder op = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null);
        ops.add(op.build());

        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.GIVEN_NAME, contact.givenName)
                .withValue(StructuredName.MIDDLE_NAME, contact.middleName)
                .withValue(StructuredName.FAMILY_NAME, contact.familyName)
                .withValue(StructuredName.PREFIX, contact.prefix)
                .withValue(StructuredName.SUFFIX, contact.suffix);
        ops.add(op.build());

        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Note.NOTE, contact.note);
        ops.add(op.build());

        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, contact.company)
                .withValue(Organization.TITLE, contact.jobTitle);
        ops.add(op.build());

        //Photo
        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, contact.avatar)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
        ops.add(op.build());

        op.withYieldAllowed(true);

        //Phones
        for (Item phone : contact.phones) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.value);

            if (phone.type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                op.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM);
                op.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, phone.label);
            } else
                op.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phone.type);

            ops.add(op.build());
        }

        //Emails
        for (Item email : contact.emails) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Email.ADDRESS, email.value)
                    .withValue(CommonDataKinds.Email.TYPE, email.type);
            ops.add(op.build());
        }
        //Postal addresses
        for (PostalAddress address : contact.postalAddresses) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.StructuredPostal.TYPE, address.type)
                    .withValue(CommonDataKinds.StructuredPostal.LABEL, address.label)
                    .withValue(CommonDataKinds.StructuredPostal.STREET, address.street)
                    .withValue(CommonDataKinds.StructuredPostal.CITY, address.city)
                    .withValue(CommonDataKinds.StructuredPostal.REGION, address.region)
                    .withValue(CommonDataKinds.StructuredPostal.POSTCODE, address.postcode)
                    .withValue(CommonDataKinds.StructuredPostal.COUNTRY, address.country);
            ops.add(op.build());
        }

        // Birthday
        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_BIRTHDAY)
                .withValue(CommonDataKinds.Event.START_DATE, contact.birthday);
        ops.add(op.build());

        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deleteContact(Contact contact) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection(ContactsContract.RawContacts.CONTACT_ID + "=?", new String[]{String.valueOf(contact.identifier)})
                .build());
        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;

        }
    }

    private boolean updateContact(Contact contact) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ContentProviderOperation.Builder op;

        // Drop all details about contact except name
        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        //Photo
        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        // Update data (name)
        op = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE})
                .withValue(StructuredName.GIVEN_NAME, contact.givenName)
                .withValue(StructuredName.MIDDLE_NAME, contact.middleName)
                .withValue(StructuredName.FAMILY_NAME, contact.familyName)
                .withValue(StructuredName.PREFIX, contact.prefix)
                .withValue(StructuredName.SUFFIX, contact.suffix);
        ops.add(op.build());

        // Insert data back into contact
        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                .withValue(Organization.TYPE, Organization.TYPE_WORK)
                .withValue(Organization.COMPANY, contact.company)
                .withValue(Organization.TITLE, contact.jobTitle);
        ops.add(op.build());

        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                .withValue(CommonDataKinds.Note.NOTE, contact.note);
        ops.add(op.build());

        //Photo
        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, contact.avatar)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
        ops.add(op.build());


        for (Item phone : contact.phones) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                    .withValue(Phone.NUMBER, phone.value);

            if (phone.type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                op.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM);
                op.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, phone.label);
            } else
                op.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phone.type);

            ops.add(op.build());
        }

        for (Item email : contact.emails) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                    .withValue(CommonDataKinds.Email.ADDRESS, email.value)
                    .withValue(CommonDataKinds.Email.TYPE, email.type);
            ops.add(op.build());
        }

        for (PostalAddress address : contact.postalAddresses) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                    .withValue(CommonDataKinds.StructuredPostal.TYPE, address.type)
                    .withValue(CommonDataKinds.StructuredPostal.STREET, address.street)
                    .withValue(CommonDataKinds.StructuredPostal.CITY, address.city)
                    .withValue(CommonDataKinds.StructuredPostal.REGION, address.region)
                    .withValue(CommonDataKinds.StructuredPostal.POSTCODE, address.postcode)
                    .withValue(CommonDataKinds.StructuredPostal.COUNTRY, address.country);
            ops.add(op.build());
        }

        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            // Log exception
            Log.e("TAG", "Exception encountered while inserting contact: ");
            e.printStackTrace();
            return false;
        }
    }

}