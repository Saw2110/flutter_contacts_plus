import Flutter
import UIKit
import Contacts
import ContactsUI

/// Logs only in debug builds. The plugin used to `print` unconditionally on every
/// form and picker open, which shipped to release builds.
private func log(_ message: @autoclosure () -> String) {
    #if DEBUG
    print("[ContactsPlugin] \(message())")
    #endif
}

@available(iOS 13.0, *)
public class SwiftContactsServicePlusPlugin: NSObject, FlutterPlugin, CNContactViewControllerDelegate, CNContactPickerDelegate {
    private var result: FlutterResult? = nil
    private var localizedLabels: Bool = true
    private weak var registrar: FlutterPluginRegistrar?
    static let FORM_OPERATION_CANCELED: Int = 1
    static let FORM_COULD_NOT_BE_OPEN: Int = 2
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "github.com/clovisnicolas/flutter_contacts", binaryMessenger: registrar.messenger())
        let instance = SwiftContactsServicePlusPlugin(registrar)
        registrar.addMethodCallDelegate(instance, channel: channel)
        instance.preLoadContactView()
    }

    init(_ registrar: FlutterPluginRegistrar) {
        self.registrar = registrar
        super.init()
    }

    /// The key window of the scene the app is actually showing, preferring the
    /// foreground-active scene so multi-window iPad setups target the right one.
    private func activeKeyWindow() -> UIWindow? {
        let windowScenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = windowScenes.first(where: { $0.activationState == .foregroundActive })
            ?? windowScenes.first
        guard let scene = scene else { return nil }

        if #available(iOS 15.0, *) {
            if let keyWindow = scene.keyWindow {
                return keyWindow
            }
        }
        // `UIWindowScene.windows` is deprecated on iOS 15+, hence the branch above.
        return scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first
    }

    private func getRootViewController() -> UIViewController? {
        log("Attempting to get root view controller...")

        // The registrar is the correct source when it is still available, but Flutter does not
        // retain registrars after registration, so `registrar` is weak and often nil by now.
        if let viewController = registrar?.viewController {
            log("Got view controller from registrar: \(type(of: viewController))")
            return viewController
        }
        log("Registrar view controller not available, trying UIScene fallback...")

        if let rootVC = activeKeyWindow()?.rootViewController {
            log("Got view controller from UIScene: \(type(of: rootVC))")
            return rootVC
        }

        // Last resort: only reachable in a non-scene app, where the delegate still owns a window.
        if let rootVC = UIApplication.shared.delegate?.window??.rootViewController {
            log("Got view controller from app delegate: \(type(of: rootVC))")
            return rootVC
        }

        log("Failed to get root view controller from all methods")
        return nil
    }

    /// The view controller that is actually on screen.
    ///
    /// Presenting on the *root* controller fails with "which is already presenting" whenever
    /// anything is already modally presented - a Flutter modal route, another plugin's sheet -
    /// so every presentation and dismissal goes through here.
    private func topViewController() -> UIViewController? {
        guard var top = getRootViewController() else { return nil }
        while let presented = top.presentedViewController {
            top = presented
        }
        return top
    }

    /// Reports a malformed method call instead of trapping on a force-cast.
    private func badArguments(_ method: String, _ detail: String) -> FlutterError {
        return FlutterError(code: "invalidArguments",
                            message: "\(method): \(detail)",
                            details: nil)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let knownMethods: Set<String> = [
            "getContacts", "getContactsForPhone", "getContactsForEmail", "getAvatar",
            "addContact", "deleteContact", "updateContact",
            "openContactForm", "openExistingContact", "openDeviceContactPicker",
        ]
        guard knownMethods.contains(call.method) else {
            result(FlutterMethodNotImplemented)
            return
        }
        // Every method takes a map. `addContact`, `deleteContact` and `updateContact` pass the
        // contact itself as `arguments`; the rest nest their values inside it.
        guard let arguments = call.arguments as? [String: Any] else {
            result(badArguments(call.method, "expected a map of arguments"))
            return
        }

        // `iOSLocalizedLabels` and the thumbnail flags are always sent by the Dart side; fall back
        // to the documented defaults rather than crashing if a caller omits them.
        let wantsThumbnails = arguments["withThumbnails"] as? Bool ?? false
        let highRes = arguments["photoHighResolution"] as? Bool ?? false
        let orderByGivenName = arguments["orderByGivenName"] as? Bool ?? true
        let iOSLocalizedLabels = arguments["iOSLocalizedLabels"] as? Bool ?? true

        switch call.method {
        case "getContacts":
            result(getContacts(query: (arguments["query"] as? String), withThumbnails: wantsThumbnails,
                               photoHighResolution: highRes, phoneQuery: false, orderByGivenName: orderByGivenName,
                               localizedLabels: iOSLocalizedLabels))
        case "getContactsForPhone":
            result(
                getContacts(
                    query: (arguments["phone"] as? String),
                    withThumbnails: wantsThumbnails,
                    photoHighResolution: highRes,
                    phoneQuery: true,
                    orderByGivenName: orderByGivenName,
                    localizedLabels: iOSLocalizedLabels
                )
            )
        case "getContactsForEmail":
            result(
                getContacts(
                    query: (arguments["email"] as? String),
                    withThumbnails: wantsThumbnails,
                    photoHighResolution: highRes,
                    phoneQuery: false,
                    emailQuery: true,
                    orderByGivenName: orderByGivenName,
                    localizedLabels: iOSLocalizedLabels
                )
            )
        case "getAvatar":
            guard let contactDict = arguments["contact"] as? [String: Any] else {
                result(badArguments(call.method, "missing 'contact'"))
                return
            }
            result(getAvatar(contact: contactDict, photoHighResolution: highRes))
        case "addContact":
            let contact = dictionaryToContact(dictionary: arguments)

            let addResult = addContact(contact: contact)
            if (addResult == "") {
                result(nil)
            }
            else {
                result(FlutterError(code: "", message: addResult, details: nil))
            }
        case "deleteContact":
            if(deleteContact(dictionary: arguments)){
                result(nil)
            }
            else{
                result(FlutterError(code: "", message: "Failed to delete contact, make sure it has a valid identifier", details: nil))
            }
        case "updateContact":
            if(updateContact(dictionary: arguments)) {
                result(nil)
            }
            else {
                result(FlutterError(code: "", message: "Failed to update contact, make sure it has a valid identifier", details: nil))
            }
        case "openContactForm":
            localizedLabels = iOSLocalizedLabels
            self.result = result
            openContactForm()
        case "openExistingContact":
            guard let contact = arguments["contact"] as? [String: Any] else {
                result(badArguments(call.method, "missing 'contact'"))
                return
            }
            localizedLabels = iOSLocalizedLabels
            self.result = result
            openExistingContact(contact: contact, result: result)
        case "openDeviceContactPicker":
            openDeviceContactPicker(arguments: arguments, result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    func getContacts(query : String?, withThumbnails: Bool, photoHighResolution: Bool, phoneQuery: Bool, emailQuery: Bool = false, orderByGivenName: Bool, localizedLabels: Bool) -> [[String:Any]]{

        var contacts : [CNContact] = []
        var result = [[String:Any]]()

        //Create the store, keys & fetch request
        let store = CNContactStore()
        var keys = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                    CNContactEmailAddressesKey,
                    CNContactPhoneNumbersKey,
                    CNContactFamilyNameKey,
                    CNContactGivenNameKey,
                    CNContactMiddleNameKey,
                    CNContactNamePrefixKey,
                    CNContactNameSuffixKey,
                    CNContactPostalAddressesKey,
                    CNContactOrganizationNameKey,
                    CNContactJobTitleKey,
                    CNContactBirthdayKey] as [Any]

        if(withThumbnails){
            if(photoHighResolution){
                keys.append(CNContactImageDataKey)
            } else {
                keys.append(CNContactThumbnailImageDataKey)
            }
        }

        let fetchRequest = CNContactFetchRequest(keysToFetch: keys as! [CNKeyDescriptor])
        // Set the predicate if there is a query
        if query != nil && !phoneQuery && !emailQuery {
            fetchRequest.predicate = CNContact.predicateForContacts(matchingName: query!)
        }

        if #available(iOS 11, *) {
            if query != nil && phoneQuery {
                let phoneNumberPredicate = CNPhoneNumber(stringValue: query!)
                fetchRequest.predicate = CNContact.predicateForContacts(matching: phoneNumberPredicate)
            } else if query != nil && emailQuery {
                fetchRequest.predicate = CNContact.predicateForContacts(matchingEmailAddress: query!)
            }
        }

        // Fetch contacts
        do{
            try store.enumerateContacts(with: fetchRequest, usingBlock: { (contact, stop) -> Void in

                if phoneQuery {
                    if #available(iOS 11, *) {
                        contacts.append(contact)
                    } else if query != nil && self.has(contact: contact, phone: query!){
                        contacts.append(contact)
                    }
                } else if emailQuery {
                    if #available(iOS 11, *) {
                        contacts.append(contact)
                    } else if query != nil && (contact.emailAddresses.contains { $0.value.caseInsensitiveCompare(query!) == .orderedSame}) {
                        contacts.append(contact)
                    }
                } else {
                    contacts.append(contact)
                }

            })
        }
        catch let error as NSError {
            log(error.localizedDescription)
            return result
        }

        if (orderByGivenName) {
            contacts = contacts.sorted { (contactA, contactB) -> Bool in
                contactA.givenName.lowercased() < contactB.givenName.lowercased()
            }
        }

        // Transform the CNContacts into dictionaries
        for contact : CNContact in contacts{
            result.append(contactToDictionary(contact: contact, localizedLabels: localizedLabels))
        }

        return result
    }

    private func has(contact: CNContact, phone: String) -> Bool {
        if (!contact.phoneNumbers.isEmpty) {
            let phoneNumberToCompareAgainst = phone.components(separatedBy: NSCharacterSet.decimalDigits.inverted).joined(separator: "")
            for phoneNumber in contact.phoneNumbers {

                if let phoneNumberStruct = phoneNumber.value as CNPhoneNumber? {
                    let phoneNumberString = phoneNumberStruct.stringValue
                    let phoneNumberToCompare = phoneNumberString.components(separatedBy: NSCharacterSet.decimalDigits.inverted).joined(separator: "")
                    if phoneNumberToCompare == phoneNumberToCompareAgainst {
                        return true
                    }
                }
            }
        }
        return false
    }

    func addContact(contact : CNMutableContact) -> String {
        let store = CNContactStore()
        do {
            let saveRequest = CNSaveRequest()
            saveRequest.add(contact, toContainerWithIdentifier: nil)
            try store.execute(saveRequest)
        }
        catch {
            return error.localizedDescription
        }
        return ""
    }

    func getAvatar(contact: [String:Any], photoHighResolution: Bool) -> FlutterStandardTypedData? {
        guard let identifier = contact["identifier"] as? String else {
            return nil
        }

        let store = CNContactStore()
        let keys = photoHighResolution ? [CNContactImageDataKey] : [CNContactThumbnailImageDataKey]

        do {
            let cnContact = try store.unifiedContact(withIdentifier: identifier, keysToFetch: keys as [CNKeyDescriptor])

            if photoHighResolution {
                if let imageData = cnContact.imageData {
                    return FlutterStandardTypedData(bytes: imageData)
                }
            } else {
                if let thumbnailData = cnContact.thumbnailImageData {
                    return FlutterStandardTypedData(bytes: thumbnailData)
                }
            }
        } catch {
            log("Error fetching avatar: \(error.localizedDescription)")
        }

        return nil
    }

    func openContactForm() {
        let contact = CNMutableContact.init()
        let controller = CNContactViewController.init(forNewContact:contact)
        controller.delegate = self
        DispatchQueue.main.async {
            let navigation = UINavigationController .init(rootViewController: controller)
            guard let viewController = self.topViewController() else {
                // Previously this returned silently, leaving the Dart future to hang forever.
                log("No view controller available to present the contact form")
                if let result = self.result {
                    result(SwiftContactsServicePlusPlugin.FORM_COULD_NOT_BE_OPEN)
                    self.result = nil
                }
                return
            }
            viewController.present(navigation, animated:true, completion: nil)
        }
    }

    func preLoadContactView() {
        DispatchQueue.main.asyncAfter(deadline: .now()+5) {
            log("Preloading CNContactViewController")
            _ = CNContactViewController.init(forNewContact: nil)
        }
    }

    @objc func cancelContactForm() {
        if let result = self.result {
            // Dismiss from the controller that is actually on screen - the form was presented on
            // the top-most controller, not necessarily the root.
            if let viewController = self.topViewController() {
                viewController.dismiss(animated: true, completion: nil)
            }
            result(SwiftContactsServicePlusPlugin.FORM_OPERATION_CANCELED)
            self.result = nil
        }
    }
    
    public func contactViewController(_ viewController: CNContactViewController, didCompleteWith contact: CNContact?) {
        viewController.dismiss(animated: true, completion: nil)
        if let result = self.result {
            if let contact = contact {
                result(contactToDictionary(contact: contact, localizedLabels: localizedLabels))
            } else {
                result(SwiftContactsServicePlusPlugin.FORM_OPERATION_CANCELED)
            }
            self.result = nil
        }
    }

    func openExistingContact(contact: [String:Any], result: @escaping FlutterResult ) {
         let store = CNContactStore()
         do {
            // Check to make sure dictionary has an identifier
             guard let identifier = contact["identifier"] as? String else{
                 self.result = nil
                 result(SwiftContactsServicePlusPlugin.FORM_COULD_NOT_BE_OPEN)
                 return
             }
            let backTitle = contact["backTitle"] as? String
            
             let keysToFetch = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                                CNContactIdentifierKey,
                                CNContactEmailAddressesKey,
                                CNContactBirthdayKey,
                                CNContactImageDataKey,
                                CNContactPhoneNumbersKey,
                                CNContactViewController.descriptorForRequiredKeys()
                ] as! [CNKeyDescriptor]
            let cnContact = try store.unifiedContact(withIdentifier: identifier, keysToFetch: keysToFetch)
            let viewController = CNContactViewController(for: cnContact)

            viewController.navigationItem.backBarButtonItem = UIBarButtonItem.init(title: backTitle == nil ? "Cancel" : backTitle, style: UIBarButtonItem.Style.plain, target: self, action: #selector(cancelContactForm))
             viewController.delegate = self
            DispatchQueue.main.async {
                let navigation = UINavigationController .init(rootViewController: viewController)
                guard let presentingVC = self.topViewController() else {
                    // Clear the stored result too, otherwise a later cancel would reply twice.
                    self.result = nil
                    result(SwiftContactsServicePlusPlugin.FORM_COULD_NOT_BE_OPEN)
                    return
                }

                let windowFrame = self.activeKeyWindow()?.frame ?? CGRect.zero

                let activityIndicatorView = UIActivityIndicatorView.init(style: .medium)
                activityIndicatorView.frame = windowFrame
                activityIndicatorView.startAnimating()
                activityIndicatorView.backgroundColor = UIColor.white
                navigation.view.addSubview(activityIndicatorView)
                presentingVC.present(navigation, animated: true, completion: nil)

                DispatchQueue.main.asyncAfter(deadline: .now()+0.5 ){
                    activityIndicatorView.removeFromSuperview()
                }
            }
         } catch {
            log(error.localizedDescription)
            self.result = nil
            result(SwiftContactsServicePlusPlugin.FORM_COULD_NOT_BE_OPEN)
         }
     }
     
    func openDeviceContactPicker(arguments: [String:Any], result: @escaping FlutterResult) {
        localizedLabels = arguments["iOSLocalizedLabels"] as? Bool ?? true
        self.result = result

        let contactPicker = CNContactPickerViewController()
        contactPicker.delegate = self
        DispatchQueue.main.async {
            guard let viewController = self.topViewController() else {
                self.result = nil
                result(FlutterError(code: "NO_VIEW_CONTROLLER", message: "Unable to get root view controller", details: nil))
                return
            }
            viewController.present(contactPicker, animated: true, completion: nil)
        }
    }

    //MARK:- CNContactPickerDelegate Method

    public func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
        if let result = self.result {
            result(contactToDictionary(contact: contact, localizedLabels: localizedLabels))
            self.result = nil
        }
    }

    public func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
        if let result = self.result {
            result(SwiftContactsServicePlusPlugin.FORM_OPERATION_CANCELED)
            self.result = nil
        }
    }
    

    func deleteContact(dictionary : [String:Any]) -> Bool{
        guard let identifier = dictionary["identifier"] as? String else{
            return false;
        }
        let store = CNContactStore()
        let keys = [CNContactIdentifierKey as NSString]
        do{
            if let contact = try store.unifiedContact(withIdentifier: identifier, keysToFetch: keys).mutableCopy() as? CNMutableContact{
                let request = CNSaveRequest()
                request.delete(contact)
                try store.execute(request)
            }
        }
        catch{
            log(error.localizedDescription)
            return false;
        }
        return true;
    }

    func updateContact(dictionary : [String:Any]) -> Bool{

        // Check to make sure dictionary has an identifier
        guard let identifier = dictionary["identifier"] as? String else{
            return false;
        }

        let store = CNContactStore()
        let keys = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                    CNContactEmailAddressesKey,
                    CNContactPhoneNumbersKey,
                    CNContactFamilyNameKey,
                    CNContactGivenNameKey,
                    CNContactMiddleNameKey,
                    CNContactNamePrefixKey,
                    CNContactNameSuffixKey,
                    CNContactPostalAddressesKey,
                    CNContactOrganizationNameKey,
                    CNContactImageDataKey,
                    CNContactJobTitleKey] as [Any]
        do {
            // Check if the contact exists
            if let contact = try store.unifiedContact(withIdentifier: identifier, keysToFetch: keys as! [CNKeyDescriptor]).mutableCopy() as? CNMutableContact{

                /// Update the contact that was retrieved from the store
                //Simple fields
                contact.givenName = dictionary["givenName"] as? String ?? ""
                contact.familyName = dictionary["familyName"] as? String ?? ""
                contact.middleName = dictionary["middleName"] as? String ?? ""
                contact.namePrefix = dictionary["prefix"] as? String ?? ""
                contact.nameSuffix = dictionary["suffix"] as? String ?? ""
                contact.organizationName = dictionary["company"] as? String ?? ""
                contact.jobTitle = dictionary["jobTitle"] as? String ?? ""
                contact.imageData = (dictionary["avatar"] as? FlutterStandardTypedData)?.data

                //Phone numbers
                if let phoneNumbers = dictionary["phones"] as? [[String:String]]{
                    var updatedPhoneNumbers = [CNLabeledValue<CNPhoneNumber>]()
                    for phone in phoneNumbers where phone["value"] != nil {
                        updatedPhoneNumbers.append(CNLabeledValue(label:getPhoneLabel(label: phone["label"]),value:CNPhoneNumber(stringValue: phone["value"]!)))
                    }
                    contact.phoneNumbers = updatedPhoneNumbers
                }

                //Emails
                if let emails = dictionary["emails"] as? [[String:String]]{
                    var updatedEmails = [CNLabeledValue<NSString>]()
                    for email in emails where nil != email["value"] {
                        let emailLabel = email["label"] ?? ""
                        updatedEmails.append(CNLabeledValue(label: getCommonLabel(label: emailLabel), value: email["value"]! as NSString))
                    }
                    contact.emailAddresses = updatedEmails
                }

                //Postal addresses
                if let postalAddresses = dictionary["postalAddresses"] as? [[String:String]]{
                    var updatedPostalAddresses = [CNLabeledValue<CNPostalAddress>]()
                    for postalAddress in postalAddresses{
                        let newAddress = CNMutablePostalAddress()
                        newAddress.street = postalAddress["street"] ?? ""
                        newAddress.city = postalAddress["city"] ?? ""
                        newAddress.postalCode = postalAddress["postcode"] ?? ""
                        newAddress.country = postalAddress["country"] ?? ""
                        newAddress.state = postalAddress["region"] ?? ""
                        let label = postalAddress["label"] ?? ""
                        updatedPostalAddresses.append(CNLabeledValue(label: getCommonLabel(label: label), value: newAddress))
                    }
                    contact.postalAddresses = updatedPostalAddresses
                }

                // Attempt to update the contact
                let request = CNSaveRequest()
                request.update(contact)
                try store.execute(request)
            }
        }
        catch {
            log(error.localizedDescription)
            return false;
        }
        return true;
    }

    func dictionaryToContact(dictionary : [String:Any]) -> CNMutableContact{
        let contact = CNMutableContact()

        //Simple fields
        contact.givenName = dictionary["givenName"] as? String ?? ""
        contact.familyName = dictionary["familyName"] as? String ?? ""
        contact.middleName = dictionary["middleName"] as? String ?? ""
        contact.namePrefix = dictionary["prefix"] as? String ?? ""
        contact.nameSuffix = dictionary["suffix"] as? String ?? ""
        contact.organizationName = dictionary["company"] as? String ?? ""
        contact.jobTitle = dictionary["jobTitle"] as? String ?? ""
        if let avatarData = (dictionary["avatar"] as? FlutterStandardTypedData)?.data {
            contact.imageData = avatarData
        }

        //Phone numbers
        if let phoneNumbers = dictionary["phones"] as? [[String:String]]{
            for phone in phoneNumbers where phone["value"] != nil {
                contact.phoneNumbers.append(CNLabeledValue(label:getPhoneLabel(label:phone["label"]),value:CNPhoneNumber(stringValue:phone["value"]!)))
            }
        }

        //Emails
        if let emails = dictionary["emails"] as? [[String:String]]{
            for email in emails where nil != email["value"] {
                let emailLabel = email["label"] ?? ""
                contact.emailAddresses.append(CNLabeledValue(label:getCommonLabel(label: emailLabel), value:email["value"]! as NSString))
            }
        }

        //Postal addresses
        if let postalAddresses = dictionary["postalAddresses"] as? [[String:String]]{
            for postalAddress in postalAddresses{
                let newAddress = CNMutablePostalAddress()
                newAddress.street = postalAddress["street"] ?? ""
                newAddress.city = postalAddress["city"] ?? ""
                newAddress.postalCode = postalAddress["postcode"] ?? ""
                newAddress.country = postalAddress["country"] ?? ""
                newAddress.state = postalAddress["region"] ?? ""
                let label = postalAddress["label"] ?? ""
                contact.postalAddresses.append(CNLabeledValue(label: getCommonLabel(label: label), value: newAddress))
            }
        }

        //BIRTHDAY
        if let birthday = dictionary["birthday"] as? String {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd"
            let date = formatter.date(from: birthday)!
            contact.birthday = Calendar.current.dateComponents([.year, .month, .day], from: date)
        }

        return contact
    }

    func contactToDictionary(contact: CNContact, localizedLabels: Bool) -> [String:Any]{

        var result = [String:Any]()

        //Simple fields
        result["identifier"] = contact.identifier
        result["displayName"] = CNContactFormatter.string(from: contact, style: CNContactFormatterStyle.fullName)
        result["givenName"] = contact.givenName
        result["familyName"] = contact.familyName
        result["middleName"] = contact.middleName
        result["prefix"] = contact.namePrefix
        result["suffix"] = contact.nameSuffix
        result["company"] = contact.organizationName
        result["jobTitle"] = contact.jobTitle
        if contact.isKeyAvailable(CNContactThumbnailImageDataKey) {
            if let avatarData = contact.thumbnailImageData {
                result["avatar"] = FlutterStandardTypedData(bytes: avatarData)
            }
        }
        if contact.isKeyAvailable(CNContactImageDataKey) {
            if let avatarData = contact.imageData {
                result["avatar"] = FlutterStandardTypedData(bytes: avatarData)
            }
        }

        //Phone numbers
        var phoneNumbers = [[String:String]]()
        for phone in contact.phoneNumbers{
            var phoneDictionary = [String:String]()
            phoneDictionary["value"] = phone.value.stringValue
            phoneDictionary["label"] = "other"
            if let label = phone.label{
                phoneDictionary["label"] = localizedLabels ? CNLabeledValue<NSString>.localizedString(forLabel: label) : getRawPhoneLabel(label);
            }
            phoneNumbers.append(phoneDictionary)
        }
        result["phones"] = phoneNumbers

        //Emails
        var emailAddresses = [[String:String]]()
        for email in contact.emailAddresses{
            var emailDictionary = [String:String]()
            emailDictionary["value"] = String(email.value)
            emailDictionary["label"] = "other"
            if let label = email.label{
                emailDictionary["label"] = localizedLabels ? CNLabeledValue<NSString>.localizedString(forLabel: label) : getRawCommonLabel(label);
            }
            emailAddresses.append(emailDictionary)
        }
        result["emails"] = emailAddresses

        //Postal addresses
        var postalAddresses = [[String:String]]()
        for address in contact.postalAddresses{
            var addressDictionary = [String:String]()
            addressDictionary["label"] = ""
            if let label = address.label{
                addressDictionary["label"] = localizedLabels ? CNLabeledValue<NSString>.localizedString(forLabel: label) : getRawCommonLabel(label);
            }
            addressDictionary["street"] = address.value.street
            addressDictionary["city"] = address.value.city
            addressDictionary["postcode"] = address.value.postalCode
            addressDictionary["region"] = address.value.state
            addressDictionary["country"] = address.value.country

            postalAddresses.append(addressDictionary)
        }
        result["postalAddresses"] = postalAddresses

        //BIRTHDAY
        if let birthday : Date = contact.birthday?.date {
            let formatter = DateFormatter()
            let year = Calendar.current.component(.year, from: birthday)
            formatter.dateFormat = year == 1 ? "--MM-dd" : "yyyy-MM-dd";
            result["birthday"] = formatter.string(from: birthday)
        }

        return result
    }

    func getPhoneLabel(label: String?) -> String{
        let labelValue = label ?? ""
        switch(labelValue){
        case "main": return CNLabelPhoneNumberMain
        case "mobile": return CNLabelPhoneNumberMobile
        case "iPhone": return CNLabelPhoneNumberiPhone
        case "work": return CNLabelWork
        case "home": return CNLabelHome
        case "other": return CNLabelOther
        default: return labelValue
        }
    }

    func getCommonLabel(label:String?) -> String{
        let labelValue = label ?? ""
        switch(labelValue){
        case "work": return CNLabelWork
        case "home": return CNLabelHome
        case "other": return CNLabelOther
        default: return labelValue
        }
    }

    func getRawPhoneLabel(_ label: String?) -> String{
        let labelValue = label ?? ""
        switch(labelValue){
            case CNLabelPhoneNumberMain: return "main"
            case CNLabelPhoneNumberMobile: return "mobile"
            case CNLabelPhoneNumberiPhone: return "iPhone"
            case CNLabelWork: return "work"
            case CNLabelHome: return "home"
            case CNLabelOther: return "other"
            default: return labelValue
        }
    }

    func getRawCommonLabel(_ label: String?) -> String{
        let labelValue = label ?? ""
        switch(labelValue){
            case CNLabelWork: return "work"
            case CNLabelHome: return "home"
            case CNLabelOther: return "other"
            default: return labelValue
        }
    }

}
