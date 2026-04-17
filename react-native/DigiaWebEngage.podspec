Pod::Spec.new do |s|
  s.name         = 'DigiaWebEngage'
  s.version      = '0.1.0'
  s.summary      = 'Digia Engage CEP plugin for WebEngage'
  s.description  = 'Bridges WebEngage in-app campaigns into Digia rendering engine for React Native'
  s.homepage     = 'https://digia.tech'
  s.license      = { :type => 'MIT' }
  s.author       = { 'Digia Technology' => 'support@digia.tech' }
  s.source       = { :path => '.' }
  s.source_files = 'ios/**/*.{h,m}'
  s.public_header_files = 'ios/WEDigiaSuppressProxy.h'
  s.header_dir   = 'DigiaWebEngage'
  s.platform     = :ios, '17.0'
  s.ios.deployment_target = '17.0'
  s.requires_arc = true
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.dependency 'React-Core'
  s.dependency 'WebEngage'
end
