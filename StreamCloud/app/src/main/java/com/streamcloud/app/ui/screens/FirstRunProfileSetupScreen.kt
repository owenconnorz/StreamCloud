package com.streamcloud.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.R
import com.streamcloud.app.data.nuvio.NuvioAccountService
import com.streamcloud.app.data.nuvio.NuvioAutoSync
import com.streamcloud.app.data.profiles.BUILT_IN_AVATAR_SEEDS
import com.streamcloud.app.data.profiles.ProfileRepository
import com.streamcloud.app.data.profiles.UserProfile
import com.streamcloud.app.data.profiles.resolveBuiltInAvatarSeed
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.tvFocusBorder
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val SetupBlue = Color(0xFF1E9FE0)
private val SetupCyan = Color(0xFF47C8F2)
private val SetupInk = Color(0xFF071321)
internal val SetupGenres = listOf(
    SetupGenre("Action", 28, Color(0xFFE88B37)),
    SetupGenre("Adventure", 12, Color(0xFF338FC4)),
    SetupGenre("Animation", 16, Color(0xFFBE75D1)),
    SetupGenre("Comedy", 35, Color(0xFFF1B938)),
    SetupGenre("Crime", 80, Color(0xFF8E78D2)),
    SetupGenre("Drama", 18, Color(0xFF478CCB)),
    SetupGenre("Family", 10751, Color(0xFF55A98D)),
    SetupGenre("Fantasy", 14, Color(0xFF895DCA)),
    SetupGenre("Horror", 27, Color(0xFFB94F58)),
    SetupGenre("Mystery", 9648, Color(0xFF4D73B6)),
    SetupGenre("Romance", 10749, Color(0xFFE16A90)),
    SetupGenre("Sci-Fi", 878, Color(0xFF36AFC2)),
    SetupGenre("Thriller", 53, Color(0xFF567398)),
    SetupGenre("Documentary", 99, Color(0xFF78A578)),
)

internal data class SetupGenre(
    val title: String,
    val tmdbId: Int,
    val color: Color,
)

private enum class SetupPage {
    Welcome,
    Nuvio,
    Profile,
    Avatar,
    Preferences,
    Complete,
}

@Composable
internal fun FirstRunProfileSetupScreen(
    repo: ProfileRepository,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val locator = remember(context) { ServiceLocator.get(context) }
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val profiles by repo.profiles.collectAsState(initial = repo.currentProfiles())
    val nuvioUserId by locator.settings.nuvioUserId.collectAsState(initial = "")
    val accessToken by locator.settings.nuvioAccessToken.collectAsState(initial = "")
    val availableProfiles = remember(profiles, nuvioUserId) {
        repo.profilesForNuvioAccount(nuvioUserId)
    }

    var page by rememberSaveable { mutableStateOf(SetupPage.Welcome) }
    var profileName by rememberSaveable { mutableStateOf("My Profile") }
    var avatarSeed by rememberSaveable { mutableStateOf(BUILT_IN_AVATAR_SEEDS.first()) }
    var avatarWasChanged by rememberSaveable { mutableStateOf(false) }
    var favoriteGenres by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var selectedProfileId by rememberSaveable { mutableStateOf("") }
    var createNewProfile by rememberSaveable { mutableStateOf(true) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var signInBusy by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf("") }
    var signInNotice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val primaryFocusRequester = remember { FocusRequester() }

    val selectedProfile = availableProfiles.firstOrNull { it.id == selectedProfileId }
    LaunchedEffect(selectedProfile?.id) {
        selectedProfile?.let { profile ->
            profileName = profile.name
            avatarSeed = profile.avatarSeed.ifBlank { profile.name }
            avatarWasChanged = false
            favoriteGenres = profile.favoriteMovieGenreIds.toSet()
        }
    }

    BackHandler(enabled = page != SetupPage.Welcome) {
        page = when (page) {
            SetupPage.Nuvio -> SetupPage.Welcome
            SetupPage.Profile -> SetupPage.Nuvio
            SetupPage.Avatar -> SetupPage.Profile
            SetupPage.Preferences -> SetupPage.Avatar
            SetupPage.Complete -> SetupPage.Preferences
            SetupPage.Welcome -> SetupPage.Welcome
        }
    }
    LaunchedEffect(page, isTv) {
        if (!isTv) return@LaunchedEffect
        repeat(10) {
            delay(60)
            if (runCatching { primaryFocusRequester.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0C4E7A), Color(0xFF092D50), Color(0xFF061522), Color(0xFF050A11)),
                ),
            ),
    ) {
        Text(
            text = "01",
            color = Color.White.copy(alpha = 0.055f),
            fontSize = if (isTv) 116.sp else 88.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = if (isTv) 54.dp else 18.dp, top = if (isTv) 56.dp else 46.dp),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = if (isTv) 56.dp else 20.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    Modifier
                        .size(if (isTv) 42.dp else 34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_streamcloud_brand),
                        contentDescription = "StreamCloud",
                        modifier = Modifier.size(if (isTv) 34.dp else 28.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "StreamCloud",
                    color = Color.White,
                    fontSize = if (isTv) 20.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (page != SetupPage.Welcome) {
                    Text(
                        "${page.ordinal} / 5",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = if (isTv) 15.sp else 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(if (isTv) 20.dp else 14.dp))
            if (page != SetupPage.Welcome) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(5) { index ->
                        val active = index < page.ordinal
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) SetupCyan else Color.White.copy(alpha = 0.18f),
                                ),
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = page,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 5 } + fadeOut())
                },
                label = "firstRunSetup",
            ) { currentPage ->
                when (currentPage) {
                    SetupPage.Welcome -> WelcomeSetupPage(
                        isTv = isTv,
                    )
                    SetupPage.Nuvio -> NuvioSetupPage(
                        isTv = isTv,
                        primaryFocusRequester = primaryFocusRequester,
                        alreadySignedIn = accessToken.isNotBlank(),
                        email = email,
                        onEmailChange = { email = it; signInError = "" },
                        password = password,
                        onPasswordChange = { password = it; signInError = "" },
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                        busy = signInBusy,
                        error = signInError,
                        notice = signInNotice,
                        onSkip = { page = SetupPage.Profile },
                        onContinue = { page = SetupPage.Profile },
                        onSignIn = {
                            if (email.isBlank() || password.isBlank()) {
                                signInError = "Enter your Nuvio email and password."
                            } else {
                                signInBusy = true
                                signInError = ""
                                scope.launch {
                                    val service = NuvioAccountService.get(context)
                                    service.signIn(email.trim(), password).fold(
                                        onSuccess = { session ->
                                            val userEmail = session.user?.email ?: email.trim()
                                            val userId = session.user?.id.orEmpty()
                                            locator.settings.setNuvioSession(
                                                session.access_token,
                                                session.refresh_token,
                                                userEmail,
                                                userId,
                                            )
                                            signInBusy = false
                                            signInNotice = "Nuvio connected. Syncing your profiles…"
                                            val syncResult = runCatching {
                                                NuvioAutoSync.syncNowDetailed(context.applicationContext)
                                            }.getOrNull()
                                            if (syncResult == null || syncResult.isFailure) {
                                                signInNotice = "Nuvio connected. You can sync your profiles later."
                                            } else {
                                                signInNotice = "Nuvio connected."
                                            }
                                            page = SetupPage.Profile
                                        },
                                        onFailure = { error ->
                                            signInBusy = false
                                            signInError = error.message ?: "Nuvio sign-in failed."
                                        },
                                    )
                                }
                            }
                        },
                    )
                    SetupPage.Profile -> ProfileSetupPage(
                        isTv = isTv,
                        profiles = availableProfiles,
                        selectedProfileId = selectedProfileId,
                        onSelectProfile = { profile ->
                            selectedProfileId = profile.id
                            createNewProfile = false
                        },
                        creatingProfile = createNewProfile || availableProfiles.isEmpty(),
                        onCreateProfile = {
                            selectedProfileId = ""
                            createNewProfile = true
                            profileName = "My Profile"
                            avatarSeed = BUILT_IN_AVATAR_SEEDS.first()
                            avatarWasChanged = false
                            favoriteGenres = emptySet()
                        },
                        profileName = profileName,
                        onProfileNameChange = { profileName = it.take(20) },
                    )
                    SetupPage.Avatar -> AvatarSetupPage(
                        isTv = isTv,
                        selectedSeed = resolveBuiltInAvatarSeed(avatarSeed),
                        onSelect = {
                            avatarSeed = it
                            avatarWasChanged = true
                        },
                    )
                    SetupPage.Preferences -> PreferenceSetupPage(
                        isTv = isTv,
                        selectedGenres = favoriteGenres,
                        onToggleGenre = { id ->
                            favoriteGenres = if (id in favoriteGenres) {
                                favoriteGenres - id
                            } else {
                                favoriteGenres + id
                            }
                        },
                    )
                    SetupPage.Complete -> SetupCompletePage(
                        isTv = isTv,
                        profileName = profileName.ifBlank { "My Profile" },
                        selectedGenreCount = favoriteGenres.size,
                    )
                }
            }

            when (page) {
                SetupPage.Welcome -> SetupPrimaryButton(
                    text = "Get started",
                    isTv = isTv,
                    focusRequester = primaryFocusRequester,
                    onClick = { page = SetupPage.Nuvio },
                )
                SetupPage.Nuvio -> Unit
                SetupPage.Profile -> SetupPrimaryButton(
                    text = "Choose an avatar",
                    isTv = isTv,
                    focusRequester = primaryFocusRequester,
                    enabled = !createNewProfile || profileName.isNotBlank() || availableProfiles.isEmpty(),
                    onClick = { page = SetupPage.Avatar },
                )
                SetupPage.Avatar -> SetupPrimaryButton(
                    text = "Continue",
                    isTv = isTv,
                    focusRequester = primaryFocusRequester,
                    onClick = { page = SetupPage.Preferences },
                )
                SetupPage.Preferences -> SetupPrimaryButton(
                    text = "Finish setup",
                    isTv = isTv,
                    focusRequester = primaryFocusRequester,
                    onClick = {
                        val profile = selectedProfile?.let { current ->
                            current.copy(
                                name = profileName.ifBlank { current.name },
                                avatarUrl = if (avatarWasChanged) "" else current.avatarUrl,
                                avatarSeed = avatarSeed,
                                favoriteMovieGenreIds = favoriteGenres.toList().sorted(),
                            )
                        } ?: UserProfile.create(profileName.ifBlank { "My Profile" }).copy(
                            avatarSeed = avatarSeed,
                            favoriteMovieGenreIds = favoriteGenres.toList().sorted(),
                        )
                        repo.saveProfile(profile)
                        repo.setActiveProfile(profile.id)
                        repo.markFirstRunSetupComplete()
                        NuvioAutoSync.request(context.applicationContext)
                        page = SetupPage.Complete
                    },
                )
                SetupPage.Complete -> SetupPrimaryButton(
                    text = "Start watching",
                    isTv = isTv,
                    focusRequester = primaryFocusRequester,
                    onClick = onDone,
                )
            }
        }
    }
}

@Composable
private fun WelcomeSetupPage(
    isTv: Boolean,
) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Make it yours",
            color = SetupCyan,
            fontWeight = FontWeight.Bold,
            fontSize = if (isTv) 18.sp else 14.sp,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Welcome to\nStreamCloud",
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (isTv) 52.sp else 38.sp,
            lineHeight = if (isTv) 58.sp else 42.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Set up a profile, pick an avatar, and tell us what you like to watch.",
            color = Color.White.copy(alpha = 0.76f),
            fontSize = if (isTv) 19.sp else 15.sp,
            lineHeight = if (isTv) 27.sp else 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.86f),
        )
        Spacer(Modifier.height(28.dp))
        SetupIllustrationCard(isTv = isTv)
    }
}

@Composable
private fun NuvioSetupPage(
    isTv: Boolean,
    primaryFocusRequester: FocusRequester,
    alreadySignedIn: Boolean,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: () -> Unit,
    busy: Boolean,
    error: String,
    notice: String,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "SYNC YOUR STREAMING PROFILE",
            color = SetupCyan,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            fontSize = if (isTv) 16.sp else 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (alreadySignedIn) "Nuvio is connected" else "Sign in to Nuvio",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (isTv) 38.sp else 29.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Sync your profiles, watch progress, and saved titles across devices. This step is optional.",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = if (isTv) 17.sp else 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = if (isTv) 24.sp else 20.sp,
            modifier = Modifier.fillMaxWidth(0.94f),
        )
        Spacer(Modifier.height(20.dp))
        if (alreadySignedIn) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                Text(
                    notice.ifBlank { "Your Nuvio account is ready to sync." },
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(18.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            SetupPrimaryButton("Continue", isTv, primaryFocusRequester, onClick = onContinue)
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text("Nuvio email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                colors = setupFieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = onPasswordVisibilityChange, enabled = !busy) {
                        Text(if (passwordVisible) "Hide" else "Show", color = SetupCyan)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = setupFieldColors(),
            )
            if (error.isNotBlank()) {
                Text(
                    error,
                    color = Color(0xFFFF9A9A),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (notice.isNotBlank()) {
                Text(
                    notice,
                    color = Color(0xFF9CE6BD),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            SetupPrimaryButton(
                text = if (busy) "Connecting…" else "Sign in",
                isTv = isTv,
                focusRequester = primaryFocusRequester,
                enabled = !busy,
                onClick = onSignIn,
            )
            TextButton(onClick = onSkip, enabled = !busy) {
                Text("Continue without Nuvio", color = Color.White.copy(alpha = 0.74f))
            }
        }
    }
}

@Composable
private fun ProfileSetupPage(
    isTv: Boolean,
    profiles: List<UserProfile>,
    selectedProfileId: String,
    onSelectProfile: (UserProfile) -> Unit,
    creatingProfile: Boolean,
    onCreateProfile: () -> Unit,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "WHO'S WATCHING?",
            color = SetupCyan,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontSize = if (isTv) 16.sp else 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (creatingProfile) "Create your profile" else "Choose a profile",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (isTv) 36.sp else 28.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Everyone gets their own watchlist and viewing progress.",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = if (isTv) 17.sp else 14.sp,
            textAlign = TextAlign.Center,
        )
        if (profiles.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    val selected = !creatingProfile && profile.id == selectedProfileId
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(if (isTv) 132.dp else 96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectProfile(profile) }
                            .padding(4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(if (isTv) 112.dp else 82.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) SetupCyan else Color.White.copy(alpha = 0.2f),
                                    RoundedCornerShape(15.dp),
                                ),
                        ) {
                            AsyncImage(
                                model = if (profile.avatarUrl.isNotBlank()) profile.avatarUrl
                                    else profileAvatarDrawable(profile.avatarSeed.ifBlank { profile.name }),
                                contentDescription = profile.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            profile.name,
                            color = Color.White,
                            fontSize = if (isTv) 15.sp else 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            TextButton(onClick = onCreateProfile) {
                Text(
                    if (creatingProfile) "Choose an existing profile" else "Add a new profile",
                    color = SetupCyan,
                )
            }
        }
        if (creatingProfile) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = profileName,
                onValueChange = onProfileNameChange,
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = setupFieldColors(),
            )
        }
    }
}

@Composable
private fun AvatarSetupPage(
    isTv: Boolean,
    selectedSeed: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
        Text(
            "CHOOSE YOUR AVATAR",
            color = SetupCyan,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontSize = if (isTv) 16.sp else 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a look you love",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (isTv) 34.sp else 26.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "You can change this whenever you want.",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = if (isTv) 17.sp else 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isTv) 7 else 5),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            gridItems(BUILT_IN_AVATAR_SEEDS) { seed ->
                val selected = seed == selectedSeed
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (isTv) 96.dp else 64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) SetupCyan else Color.White.copy(alpha = 0.22f),
                            RoundedCornerShape(14.dp),
                        )
                        .then(
                            if (isTv) Modifier.tvFocusBorder(
                                RoundedCornerShape(14.dp),
                                color = SetupCyan,
                                borderWidth = 3.dp,
                            ) else Modifier,
                        )
                        .clickable { onSelect(seed) },
                ) {
                    AsyncImage(
                        model = profileAvatarDrawable(seed),
                        contentDescription = seed.removePrefix("avatar_").replace('_', ' '),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )
                    if (selected) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(SetupBlue),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceSetupPage(
    isTv: Boolean,
    selectedGenres: Set<Int>,
    onToggleGenre: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
        Text(
            "MAKE YOUR HOME FEEL LIKE YOURS",
            color = SetupCyan,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.25.sp,
            fontSize = if (isTv) 15.sp else 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "What do you like to watch?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (isTv) 34.sp else 26.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Choose a few genres. We’ll bring matching titles higher in your home picks.",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = if (isTv) 17.sp else 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = if (isTv) 23.sp else 19.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isTv) 4 else 2),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            gridItems(SetupGenres, key = { it.tmdbId }) { genre ->
                val selected = genre.tmdbId in selectedGenres
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(if (isTv) 68.dp else 54.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            if (selected) genre.color.copy(alpha = 0.28f)
                            else Color.White.copy(alpha = 0.07f),
                        )
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) genre.color else Color.White.copy(alpha = 0.16f),
                            RoundedCornerShape(15.dp),
                        )
                        .then(
                            if (isTv) Modifier.tvFocusBorder(
                                RoundedCornerShape(15.dp),
                                color = SetupCyan,
                                borderWidth = 3.dp,
                            ) else Modifier,
                        )
                        .clickable { onToggleGenre(genre.tmdbId) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(if (isTv) 12.dp else 10.dp)
                            .clip(CircleShape)
                            .background(genre.color),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        genre.title,
                        color = Color.White,
                        fontSize = if (isTv) 17.sp else 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Text(
            "${selectedGenres.size} selected · You can change this later",
            color = Color.White.copy(alpha = 0.66f),
            fontSize = if (isTv) 14.sp else 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SetupCompletePage(
    isTv: Boolean,
    profileName: String,
    selectedGenreCount: Int,
) {
    Column(
        Modifier.fillMaxSize().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(if (isTv) 104.dp else 80.dp)
                .clip(CircleShape)
                .background(SetupBlue.copy(alpha = 0.2f))
                .border(2.dp, SetupCyan, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = SetupCyan,
                modifier = Modifier.size(if (isTv) 54.dp else 42.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "You're all set, $profileName",
            color = Color.White,
            fontSize = if (isTv) 38.sp else 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (selectedGenreCount == 0) {
                "Your profile is ready. Explore and save what you love."
            } else {
                "Your home picks are tuned to $selectedGenreCount genres. Enjoy discovering something new."
            },
            color = Color.White.copy(alpha = 0.75f),
            fontSize = if (isTv) 18.sp else 14.sp,
            lineHeight = if (isTv) 25.sp else 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.9f),
        )
    }
}

@Composable
private fun SetupIllustrationCard(isTv: Boolean) {
    Row(
        Modifier
            .fillMaxWidth(if (isTv) 0.72f else 0.9f)
            .height(if (isTv) 176.dp else 116.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(
            Color(0xFF1677B4),
            Color(0xFF235BA4),
            Color(0xFF3485C0),
        ).forEachIndexed { index, color ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.9f), SetupInk.copy(alpha = 0.78f)),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        listOf("01", "02", "03")[index],
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = if (isTv) 16.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOf("Profile", "Avatar", "Discover")[index],
                        color = Color.White,
                        fontSize = if (isTv) 16.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupPrimaryButton(
    text: String,
    isTv: Boolean,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SetupBlue,
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.16f),
            disabledContentColor = Color.White.copy(alpha = 0.45f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isTv) 60.dp else 54.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .then(
                if (isTv) Modifier.tvFocusBorder(
                    RoundedCornerShape(15.dp),
                    color = SetupCyan,
                    borderWidth = 3.dp,
                ) else Modifier,
            ),
    ) {
        Text(
            text,
            fontSize = if (isTv) 18.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun setupFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = SetupCyan,
    unfocusedBorderColor = Color.White.copy(alpha = 0.24f),
    focusedLabelColor = SetupCyan,
    unfocusedLabelColor = Color.White.copy(alpha = 0.64f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = SetupCyan,
)
