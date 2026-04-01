/** @type {import('jest').Config} */
module.exports = {
    preset: 'ts-jest',
    testEnvironment: 'node',
    roots: ['<rootDir>/__tests__'],
    testMatch: ['**/__tests__/**/*.test.ts'],
    transform: {
        '^.+\\.tsx?$': 'ts-jest',
    },
    moduleNameMapper: {
        // Stub the native WebEngage SDK — tests use a FakeBridge instead
        'webengage-react-native': '<rootDir>/__tests__/__mocks__/webengage-react-native.ts',
    },
    forceExit: true,
    collectCoverageFrom: ['src/**/*.ts'],
};
