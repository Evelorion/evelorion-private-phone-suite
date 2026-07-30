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
      '42:EE:C7:7E:B4:5F:B0:04:AB:74:F4:ED:51:5B:2F:6E:7D:C9:8B:C4:FA:A0:51:AD:86:03:83:17:D5:30:5D:F0',
  },
  {
    packageName: 'com.evelorion.contacts',
    sha256CertFingerprint:
      '4E:1F:E6:C4:4A:DB:A5:C5:88:B1:25:7C:D4:72:27:44:0B:15:B1:61:AE:6A:29:B0:DB:F8:B0:94:56:19:F6:22',
  },
  {
    packageName: 'com.evelorion.contacts.debug',
    sha256CertFingerprint:
      '4E:1F:E6:C4:4A:DB:A5:C5:88:B1:25:7C:D4:72:27:44:0B:15:B1:61:AE:6A:29:B0:DB:F8:B0:94:56:19:F6:22',
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
