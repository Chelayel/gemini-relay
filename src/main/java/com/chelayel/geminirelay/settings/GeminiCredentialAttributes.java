package com.chelayel.geminirelay.settings;

import com.intellij.credentialStore.CredentialAttributes;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the {@link CredentialAttributes} that address this plugin's PasswordSafe
 * entries (the Gemini API key and the Apigee client secret).
 *
 * <p>This one helper is Java rather than Kotlin on purpose. In the 2024.2 SDK we
 * compile against, {@code CredentialAttributes} declares everything after
 * {@code serviceName} as Kotlin default arguments, so <em>every</em> Kotlin call
 * site — including the single-argument {@code CredentialAttributes(serviceName)}
 * form — compiles down to the synthetic defaults bridge
 * {@code <init>(String, String, Class, boolean, int, DefaultConstructorMarker)}.
 * Current platform builds have dropped the {@code requestor} parameter and keep
 * that bridge only as a deprecated shim, which is the usage the JetBrains
 * Marketplace verifier reports. The {@code @JvmOverloads}-generated
 * {@code <init>(String)} overload is invisible to Kotlin callers but is a plain
 * public constructor to Java, and it is not deprecated in any build.
 *
 * <p>The attributes produced here are field-for-field identical to the previous
 * {@code CredentialAttributes(serviceName, null)} call — same service name, null
 * user name, {@code isPasswordMemoryOnly=false}, {@code cacheDeniedItems=true} —
 * so secrets stored by earlier versions of the plugin still resolve after an
 * update.
 */
final class GeminiCredentialAttributes {

    private GeminiCredentialAttributes() {
    }

    /** Attributes for {@code serviceName} with no user name. */
    static @NotNull CredentialAttributes forService(@NotNull String serviceName) {
        return new CredentialAttributes(serviceName);
    }
}
