#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#
Pod::Spec.new do |s|
  s.name             = 'contacts_service_plus'
  s.version          = '0.0.4'
  s.summary          = 'A Flutter plugin to retrieve and manage contacts on Android and iOS devices.'
  s.description      = <<-DESC
A Flutter plugin to retrieve and manage contacts on Android and iOS devices.
                       DESC
  s.homepage         = 'https://github.com/Saw2110/flutter_contacts_plus'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Saw2110' => 'https://github.com/Saw2110' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'

  s.ios.deployment_target = '13.0'
  s.swift_version         = '5.0'
end
