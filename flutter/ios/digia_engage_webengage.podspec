Pod::Spec.new do |s|
  s.name             = 'digia_engage_webengage'
  s.version          = '0.1.0'
  s.summary          = 'Digia Engage CEP plugin for WebEngage.'
  s.description      = <<-DESC
  Bridges WebEngage in-app and personalization campaigns into Digia's rendering engine.
                       DESC
  s.homepage         = 'https://digia.tech'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Digia Technology' => 'dev@digia.tech' }
  s.source           = { :path => '.' }
  s.source_files        = 'Classes/**/*'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'
  s.dependency 'WebEngage', '>= 6.10.0'
  s.platform            = :ios, '12.0'
  # s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
end
