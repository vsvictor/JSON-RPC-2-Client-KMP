package com.vsvictor.jsonrpckmp

// Public self-signed certificate of the local json-rpc-2 test server (no private key).
internal const val TEST_SERVER_CA_PEM = """Certificate:
    Data:
        Version: 3 (0x2)
        Serial Number:
            35:bd:c3:fb:f4:31:3a:80:dc:d9:69:11:5f:98:14:15:ef:86:20:ab
        Signature Algorithm: ecdsa-with-SHA256
        Issuer: O=JSON-RPC 2.0 local network, CN=mobilespace.local
        Validity
            Not Before: Oct 17 15:50:57 2026 GMT
            Not After : Jan 19 15:50:57 2029 GMT
        Subject: O=JSON-RPC 2.0 local network, CN=mobilespace.local
        Subject Public Key Info:
            Public Key Algorithm: id-ecPublicKey
                Public-Key: (256 bit)
                pub:
                    04:7a:f8:5d:3f:e0:a8:7f:02:19:6d:c6:eb:3a:bd:
                    1f:f7:09:64:7b:43:38:76:12:68:6a:3e:3b:f8:68:
                    1b:55:27:70:3d:f8:3a:77:43:83:94:7c:f9:10:f4:
                    45:16:aa:ed:eb:83:78:07:cd:35:37:b3:8f:19:89:
                    59:0d:a2:fc:ee
                ASN1 OID: prime256v1
                NIST CURVE: P-256
        X509v3 extensions:
            X509v3 Subject Key Identifier: 
                93:BE:B1:F3:A7:70:25:9E:BC:5B:58:56:1E:63:43:3D:DD:14:BF:19
            X509v3 Authority Key Identifier: 
                93:BE:B1:F3:A7:70:25:9E:BC:5B:58:56:1E:63:43:3D:DD:14:BF:19
            X509v3 Subject Alternative Name: 
                DNS:mobilespace.local, IP Address:192.168.0.160, IP Address:2A03:7380:214:6B30:C80:77A4:ABC7:9FEB, DNS:localhost, IP Address:127.0.0.1, IP Address:0:0:0:0:0:0:0:1
            X509v3 Basic Constraints: critical
                CA:FALSE
            X509v3 Key Usage: critical
                Digital Signature
            X509v3 Extended Key Usage: 
                TLS Web Server Authentication
    Signature Algorithm: ecdsa-with-SHA256
    Signature Value:
        30:44:02:20:4c:9c:b7:f2:1c:be:ed:3b:79:39:eb:37:83:df:
        ab:56:cb:99:d9:6b:07:8d:02:f2:50:ae:ed:61:66:fa:99:c7:
        02:20:6e:09:fe:d1:30:ca:28:f0:9e:be:8d:15:92:06:d3:9e:
        39:31:9a:1d:98:d6:1d:6e:2a:14:2e:95:84:14:7f:6a
-----BEGIN CERTIFICATE-----
MIICUzCCAfqgAwIBAgIUNb3D+/QxOoDc2WkRX5gUFe+GIKswCgYIKoZIzj0EAwIw
QTEjMCEGA1UECgwaSlNPTi1SUEMgMi4wIGxvY2FsIG5ldHdvcmsxGjAYBgNVBAMM
EW1vYmlsZXNwYWNlLmxvY2FsMB4XDTI2MTAxNzE1NTA1N1oXDTI5MDExOTE1NTA1
N1owQTEjMCEGA1UECgwaSlNPTi1SUEMgMi4wIGxvY2FsIG5ldHdvcmsxGjAYBgNV
BAMMEW1vYmlsZXNwYWNlLmxvY2FsMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE
evhdP+CofwIZbcbrOr0f9wlke0M4dhJoaj47+GgbVSdwPfg6d0ODlHz5EPRFFqrt
64N4B801N7OPGYlZDaL87qOBzzCBzDAdBgNVHQ4EFgQUk76x86dwJZ68W1hWHmND
Pd0UvxkwHwYDVR0jBBgwFoAUk76x86dwJZ68W1hWHmNDPd0UvxkwVwYDVR0RBFAw
ToIRbW9iaWxlc3BhY2UubG9jYWyHBMCoAKCHECoDc4ACFGswDIB3pKvHn+uCCWxv
Y2FsaG9zdIcEfwAAAYcQAAAAAAAAAAAAAAAAAAAAATAMBgNVHRMBAf8EAjAAMA4G
A1UdDwEB/wQEAwIHgDATBgNVHSUEDDAKBggrBgEFBQcDATAKBggqhkjOPQQDAgNH
ADBEAiBMnLfyHL7tO3k56zeD36tWy5nZaweNAvJQru1hZvqZxwIgbgn+0TDKKPCe
vo0VkgbTnjkxmh2Y1h1uKhQulYQUf2o=
-----END CERTIFICATE-----
"""
