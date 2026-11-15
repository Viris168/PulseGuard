package com.viris.PulseGuard.check;

import com.viris.PulseGuard.common.net.SafeUrlValidator;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Name resolution for the check client that only ever yields addresses {@link SafeUrlValidator}
 * has cleared (rule 6).
 *
 * <p>Without it the validator and Netty each resolve the host: a rebinding domain with a zero
 * TTL answers a public address to the first lookup and {@code 127.0.0.1} or
 * {@code 169.254.169.254} to the second. Here the validator's answer is the one connected to,
 * and the check fails before any packet leaves when it is private.
 *
 * <p>IP literals go through the validator too ({@code doIsResolved} is always false), and the
 * blocking JDK lookup runs on a virtual thread, never on a Netty event loop shared with every
 * other check.
 */
final class PublicAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final SafeUrlValidator validator;
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();

    PublicAddressResolverGroup(SafeUrlValidator validator) {
        this.validator = validator;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {

            @Override
            protected boolean doIsResolved(InetSocketAddress address) {
                return false;
            }

            @Override
            protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                lookups.execute(() -> {
                    try {
                        promise.trySuccess(new InetSocketAddress(publicAddresses(address)[0], address.getPort()));
                    } catch (RuntimeException e) {
                        promise.tryFailure(e);
                    }
                });
            }

            @Override
            protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                lookups.execute(() -> {
                    try {
                        promise.trySuccess(Arrays.stream(publicAddresses(address))
                                .map(ip -> new InetSocketAddress(ip, address.getPort()))
                                .toList());
                    } catch (RuntimeException e) {
                        promise.tryFailure(e);
                    }
                });
            }
        };
    }

    private InetAddress[] publicAddresses(InetSocketAddress address) {
        return validator.resolvePublic(address.getHostString());
    }

    @Override
    public void close() {
        super.close();
        lookups.shutdown();
    }
}
