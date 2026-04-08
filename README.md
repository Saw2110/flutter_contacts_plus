# contacts_service_plus 

> **Note:** This package is a fork of [contacts_service](https://pub.dev/packages/contacts_service) | Flutter package for latest support and enhanced functionality.

A Flutter plugin to access and manage the device's native contacts with full CRUD operations support.

## Features

- ✅ Read all contacts from device
- ✅ Search contacts by name/query
- ✅ Add new contacts
- ✅ Update existing contacts  
- ✅ Delete contacts
- ✅ Access contact avatars/thumbnails
- ✅ Native contact form integration
- ✅ Cross-platform support (iOS & Android)

## Installation

Add `contacts_service_plus` to your `pubspec.yaml`:

```yaml
dependencies:
  contacts_service_plus: ^0.0.3
```

Then run:
```bash
flutter pub get
```

## Platform Setup

### Android Configuration

Add these permissions to your `android/app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.WRITE_CONTACTS" />
```

### iOS Configuration

Add the usage description to your `ios/Runner/Info.plist`:

```xml
<key>NSContactsUsageDescription</key>
<string>This app requires contacts access to function properly.</string>
```

## Permissions Handling

> **Important:** This plugin does not handle permission requests. You must handle permissions yourself using one of these recommended packages:
> - [permission_handler](https://pub.dartlang.org/packages/permission_handler) (Recommended)
> - [flutter_simple_permissions](https://github.com/AppleEducate/flutter_simple_permissions)

**For testing:** You can manually grant permissions in your device settings: Settings → Apps → [Your App] → Permissions → Contacts

## Usage Examples

### Basic Operations

```dart
import 'package:contacts_service_plus/contacts_service_plus.dart';

// Get all contacts
List<Contact> contacts = await ContactsService.getContacts();

// Get contacts without thumbnails (faster performance)
List<Contact> contacts = await ContactsService.getContacts(withThumbnails: false);

// Search contacts by name
List<Contact> johns = await ContactsService.getContacts(query: "john");
```

### CRUD Operations

```dart
// Create a new contact
Contact newContact = Contact(
  givenName: "John",
  familyName: "Doe", 
  emails: [Item(label: "work", value: "john.doe@example.com")],
  phones: [Item(label: "mobile", value: "+1234567890")]
);

// Add contact (requires firstName/lastName)
await ContactsService.addContact(newContact);

// Update contact (requires valid identifier)
await ContactsService.updateContact(contact);

// Delete contact (requires valid identifier) 
await ContactsService.deleteContact(contact);
```

### Avatar Management

```dart
// Android only: Get contact avatar separately
Uint8List? avatar = await ContactsService.getAvatar(contact);
```

### Native Forms Integration

```dart
try {
  // Open native contact creation form
  await ContactsService.openContactForm();
  
  // Edit existing contact in native form (requires valid identifier)
  await ContactsService.openExistingContact(contact);
} catch (e) {
  // Handle form errors (user cancellation, etc.)
  print("Contact form error: $e");
}
```

## Contact Model Structure

```dart
class Contact {
  // Name fields
  String? displayName;
  String? givenName;
  String? middleName; 
  String? prefix;
  String? suffix;
  String? familyName;

  // Professional info
  String? company;
  String? jobTitle;

  // Contact methods
  List<Item> emails = [];
  List<Item> phones = [];
  List<PostalAddress> postalAddresses = [];

  // Avatar
  Uint8List? avatar;
}
```

### Item Model
```dart
class Item {
  String? label;  // e.g., "work", "home", "mobile"
  String? value;  // The actual email/phone value
}
```

## Example Screenshot

![Example Usage](doc/example.gif "Contact management example")

## Error Handling

```dart
try {
  List<Contact> contacts = await ContactsService.getContacts();
  // Process contacts
} catch (e) {
  // Handle permission errors or other exceptions
  print("Error accessing contacts: $e");
}
```

## Troubleshooting

### Common Issues

1. **App crashes on contact access**
   - Ensure permissions are properly configured and granted
   - Check that you're requesting permissions before using the plugin

2. **Empty contact list**
   - Verify device has contacts saved
   - Ensure READ_CONTACTS permission is granted

3. **Cannot add/update contacts**
   - Verify WRITE_CONTACTS permission is granted
   - Ensure contact has required fields (firstName/lastName for new contacts)

## Contributing

We welcome contributions! Please:

1. **Report bugs** by creating an issue
2. **Request features** through feature requests  
3. **Submit code** via pull requests to the `staging` branch

### Development Setup
```bash
git clone [your-repo-url]
cd contacts_service_plus
flutter pub get
```

## Credits
- Forked from the original [contacts_service](https://pub.dev/packages/contacts_service) package

