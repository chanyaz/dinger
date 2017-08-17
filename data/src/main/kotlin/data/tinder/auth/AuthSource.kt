package data.tinder.auth

import com.nytimes.android.external.store3.base.impl.Store
import data.network.RequestSource
import dagger.Lazy as DaggerLazy

internal class AuthSource(storeAccessor: DaggerLazy<Store<AuthResponse, AuthRequestParameters>>)
    : RequestSource<AuthRequestParameters, AuthResponse>(storeAccessor.get())