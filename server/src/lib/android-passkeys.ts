export type AndroidPasskeyApp = {
  packageName: string;
  sha256CertFingerprint: string;
};

/**
 * Android passkeys identify a native caller by package signing certificate.
 * Certificate fingerprints are public identifiers, not signing secrets.
 */
export const ANDROID_PASSKEY_APPS: readonly AndroidPasskeyApp[] = [
  {
    packageName: 'com.evelorion.contacts',
    sha256CertFingerprint:
      '12:7D:2F:23:C9:08:68:B2:67:01:6C:66:F0:1A:4B:55:50:E2:A0:4C:4A:2C:B2:5C:60:00:46:7C:F6:61:1B:4B',
  },
];

export function androidAuthenticationOrigins(): string[] {
  return ANDROID_PASSKEY_APPS.map(({ sha256CertFingerprint }) => {
    const certSha256 = Buffer.from(sha256CertFingerprint.replaceAll(':', ''), 'hex');
    const apkKeyHash = certSha256.toString('base64url');
    return `android:apk-key-hash:${apkKeyHash}`;
  });
}

export function digitalAssetLinks() {
  return ANDROID_PASSKEY_APPS.map(({ packageName, sha256CertFingerprint }) => ({
    relation: [
      'delegate_permission/common.handle_all_urls',
      'delegate_permission/common.get_login_creds',
    ],
    target: {
      namespace: 'android_app',
      package_name: packageName,
      sha256_cert_fingerprints: [sha256CertFingerprint],
    },
  }));
}
