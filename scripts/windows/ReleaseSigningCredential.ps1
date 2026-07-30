function Get-EvelorionReleaseSigningSecret {
    if (-not ("EvelorionReleaseCredentialReader" -as [type])) {
        Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class EvelorionReleaseCredentialReader {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct Credential {
        public UInt32 Flags;
        public UInt32 Type;
        public IntPtr TargetName;
        public IntPtr Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public UInt32 CredentialBlobSize;
        public IntPtr CredentialBlob;
        public UInt32 Persist;
        public UInt32 AttributeCount;
        public IntPtr Attributes;
        public IntPtr TargetAlias;
        public IntPtr UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredReadW",
        CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(
        string target, UInt32 type, UInt32 flags, out IntPtr credential);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    public static string Read(string target) {
        IntPtr pointer;
        if (!CredRead(target, 1, 0, out pointer)) {
            throw new System.ComponentModel.Win32Exception(
                Marshal.GetLastWin32Error());
        }
        try {
            var credential = (Credential)Marshal.PtrToStructure(
                pointer, typeof(Credential));
            return Marshal.PtrToStringUni(
                credential.CredentialBlob,
                (int)credential.CredentialBlobSize / 2);
        } finally {
            CredFree(pointer);
        }
    }
}
'@
    }

    [EvelorionReleaseCredentialReader]::Read(
        "Evelorion.PrivatePhoneSuite.ReleaseSigning")
}
