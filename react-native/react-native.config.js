module.exports = {
    dependency: {
        platforms: {
            android: {
                sourceDir: './android',
                packageImportPath: 'import com.digia.engage.webengage.DigiaSuppressPackage;',
                packageInstance: 'new DigiaSuppressPackage()',
            },
            // iOS uses RCT_EXPORT_MODULE and is discovered automatically via CocoaPods.
            ios: {},
        },
    },
};
