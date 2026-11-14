<template>
  <Teleport to=".app">
    <div
      class="tvSearchOverlay"
      tabindex="-1"
      @click.self="emitClose"
      @keydown.enter.self="emitClose"
      @keydown.space.self="emitClose"
    >
      <div
        class="tvSearchCard ft-card"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="id"
      >
        <h2
          :id="id"
          class="tvSearchTitle"
        >
          {{ t('Search / Go to URL') }}
        </h2>

        <div class="tvSearchInputRow">
          <FtInput
            ref="searchInput"
            :placeholder="t('Search / Go to URL')"
            class="tvSearchInput"
            is-search
            read-only
            :data-list="activeDataList"
            :data-list-properties="activeDataListProperties"
            show-clear-text-button
            show-data-when-empty
            @input="onFtInput"
            @click="goToSearch"
            @clear="handleClear"
            @remove="removeSearchHistoryEntryInDbAndCache"
          />
          <button
            class="tvSearchFilterButton navButton"
            :class="{ filterChanged: searchFilterValueChanged }"
            :aria-label="t('Search Filters.Search Filters')"
            :title="t('Search Filters.Search Filters')"
            @click="showSearchFilters"
          >
            <FontAwesomeIcon
              class="navIcon"
              :icon="['fas', 'filter']"
            />
          </button>
        </div>

        <TvVirtualKeyboard
          ref="keyboard"
          @input="onVirtualInput"
          @backspace="onVirtualBackspace"
          @space="onVirtualSpace"
          @enter="onVirtualEnter"
          @clear="handleClear"
        />

        <div class="tvSearchActions">
          <button
            class="tvSearchCloseButton"
            @click="emitClose"
          >
            {{ t('Close') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, useId, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'

import FtInput from '../FtInput/FtInput.vue'
import TvVirtualKeyboard from '../TvVirtualKeyboard/TvVirtualKeyboard.vue'

import store from '../../store/index'

import { SEARCH_RESULTS_DISPLAY_LIMIT } from '../../../constants'
import { debounce, openInternalPath } from '../../helpers/utils'
import { clearLocalSearchSuggestionsSession, getLocalSearchSuggestions } from '../../helpers/api/local'
import { getInvidiousSearchSuggestions } from '../../helpers/api/invidious'
import android from 'android'

const { t } = useI18n()

const emit = defineEmits(['close'])

const id = useId()
const searchInput = useTemplateRef('searchInput')
const keyboard = useTemplateRef('keyboard')

const searchSuggestionsDataList = shallowRef([])
const lastSuggestionQuery = ref('')
const currentQuery = ref('')

const enableSearchSuggestions = computed(() => store.getters.getEnableSearchSuggestions)
const searchFilterValueChanged = computed(() => store.getters.getSearchFilterValueChanged)
const searchSettings = computed(() => store.getters.getSearchSettings)

const usingOnlySearchHistoryResults = computed(() => lastSuggestionQuery.value.length === 0)

const latestMatchingSearchHistoryNames = computed(() => {
  return store.getters.getLatestMatchingSearchHistoryNames(lastSuggestionQuery.value)
})

const latestSearchHistoryNames = computed(() => store.getters.getLatestSearchHistoryNames)

const activeDataList = computed(() => {
  if (usingOnlySearchHistoryResults.value) {
    return latestSearchHistoryNames.value
  }

  const searchResults = [...latestMatchingSearchHistoryNames.value]

  if (enableSearchSuggestions.value) {
    for (const searchSuggestion of searchSuggestionsDataList.value) {
      if (latestMatchingSearchHistoryNames.value.includes(searchSuggestion)) {
        continue
      }

      searchResults.push(searchSuggestion)

      if (searchResults.length === SEARCH_RESULTS_DISPLAY_LIMIT) {
        break
      }
    }
  }

  return searchResults
})

const activeDataListProperties = computed(() => {
  const searchHistoryEntriesCount = usingOnlySearchHistoryResults.value
    ? latestSearchHistoryNames.value.length
    : latestMatchingSearchHistoryNames.value.length

  const properties = []

  for (let i = 0; i < activeDataList.value.length; i++) {
    properties.push(i < searchHistoryEntriesCount
      ? { isRemoveable: true, iconName: 'clock-rotate-left' }
      : { isRemoveable: false, iconName: 'magnifying-glass' }
    )
  }

  return properties
})

const backendPreference = computed(() => store.getters.getBackendPreference)
const backendFallback = computed(() => store.getters.getBackendFallback)

function emitClose() {
  emit('close')
}

function showSearchFilters() {
  store.dispatch('showSearchFilters')
}

function clearLastSuggestionQuery() {
  lastSuggestionQuery.value = ''
}

function onFtInput(query) {
  currentQuery.value = query
  getSearchSuggestionsDebounce(query)
}

function handleClear() {
  currentQuery.value = ''
  searchInput.value?.setText('')
  clearLastSuggestionQuery()
}

function onVirtualInput(char) {
  currentQuery.value += char
  searchInput.value?.setText(currentQuery.value)
  getSearchSuggestionsDebounce(currentQuery.value)
}

function onVirtualBackspace() {
  if (currentQuery.value.length > 0) {
    currentQuery.value = currentQuery.value.slice(0, -1)
    searchInput.value?.setText(currentQuery.value)
    getSearchSuggestionsDebounce(currentQuery.value)
  } else {
    handleClear()
  }
}

function onVirtualSpace() {
  onVirtualInput(' ')
}

function onVirtualEnter() {
  if (currentQuery.value.trim().length > 0) {
    goToSearch(currentQuery.value)
  } else {
    // if empty, just close or do nothing
    emitClose()
  }
}

function removeSearchHistoryEntryInDbAndCache(query) {
  store.dispatch('removeSearchHistoryEntry', query)
  store.commit('removeFromSessionSearchHistory', query)
}

function getSearchSuggestionsDebounce(query) {
  if (query === lastSuggestionQuery.value) {
    return
  }

  lastSuggestionQuery.value = query

  if (enableSearchSuggestions.value) {
    debounceSearchResults(query.trim())
  }
}

const debounceSearchResults = debounce((query) => {
  if (!process.env.SUPPORTS_LOCAL_API || backendPreference.value === 'invidious') {
    getSearchSuggestionsInvidious(query)
  } else {
    getSearchSuggestionsLocal(query)
  }
}, 200)

async function getSearchSuggestionsLocal(query) {
  searchSuggestionsDataList.value = query.length > 0
    ? await getLocalSearchSuggestions(query)
    : []
}

async function getSearchSuggestionsInvidious(query) {
  if (query === '') {
    searchSuggestionsDataList.value = []
    return
  }

  try {
    searchSuggestionsDataList.value = (await getInvidiousSearchSuggestions(query)).suggestions
  } catch (err) {
    console.error(err)

    if (process.env.SUPPORTS_LOCAL_API && backendFallback.value) {
      console.error(
        'Error gettings search suggestions.  Falling back to Local API'
      )
      getSearchSuggestionsLocal(query)
    }
  }
}

function goToSearch(queryText, { event } = {}) {
  const doCreateNewWindow = event && event.shiftKey

  clearLocalSearchSuggestionsSession()

  // Close the overlay first so the navigation isn't blocked by the overlay
  // and so the back stack is clean. Keep a local copy of queryText.
  emit('close')

  store.dispatch('getYoutubeUrlInfo', queryText).then((result) => {
    switch (result.urlType) {
      case 'video': {
        const { videoId, timestamp, playlistId } = result

        const query = {}
        if (timestamp) {
          query.timestamp = timestamp
        }
        if (playlistId && playlistId.length > 0) {
          query.playlistId = playlistId
        }

        openInternalPath({
          path: `/watch/${videoId}`,
          query,
          doCreateNewWindow,
          searchQueryText: queryText,
        })
        break
      }

      case 'playlist': {
        const { playlistId, query } = result

        openInternalPath({
          path: `/playlist/${playlistId}`,
          query,
          doCreateNewWindow,
          searchQueryText: queryText,
        })
        break
      }

      case 'search': {
        const { searchQuery, query } = result

        openInternalPath({
          path: `/search/${encodeURIComponent(searchQuery)}`,
          query,
          doCreateNewWindow,
          searchQueryText: searchQuery,
        })
        break
      }

      case 'hashtag': {
        const { hashtag } = result
        openInternalPath({
          path: `/hashtag/${encodeURIComponent(hashtag)}`,
          doCreateNewWindow,
          searchQueryText: `#${hashtag}`,
        })

        break
      }

      case 'post': {
        const { postId, query } = result

        openInternalPath({
          path: `/post/${postId}`,
          query,
          doCreateNewWindow,
          searchQueryText: queryText,
        })
        break
      }

      case 'channel': {
        const { channelId, subPath, url } = result

        openInternalPath({
          path: `/channel/${channelId}/${subPath}`,
          doCreateNewWindow,
          query: {
            url,
          },
          searchQueryText: queryText,
        })
        break
      }

      case 'trending':
      case 'subscriptions':
      case 'history':
      case 'userplaylists':
        openInternalPath({
          path: `/${result.urlType}`,
          doCreateNewWindow,
          searchQueryText: queryText
        })
        break

      case 'invalid_url':
      default: {
        openInternalPath({
          path: `/search/${encodeURIComponent(queryText)}`,
          query: {
            prioritize: searchSettings.value.prioritize,
            time: searchSettings.value.time,
            type: searchSettings.value.type,
            duration: searchSettings.value.duration,
            features: [...searchSettings.value.features],
          },
          doCreateNewWindow,
          searchQueryText: queryText,
        })
      }
    }

    if (doCreateNewWindow) {
      // Query text copied to new window = can be removed from current window
      searchInput.value?.setText('')
    }
  })
}

let lastActiveElement = null

function handleEscape(event) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    emit('close')
    return
  }

  // The input keeps Up and Down for walking its suggestions, and on its own it wraps
  // from the last one back to the top, which leaves no way down to the keyboard. So
  // Down from the last suggestion, or with none showing, moves on to the keys instead.
  if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
    return
  }

  const active = document.activeElement

  // Up from the top row of keys goes to the input. Left to spatial navigation it lands
  // on whatever is straight above, which for the first key is the input's clear button,
  // invisible until the input has focus.
  if (event.key === 'ArrowUp' && active?.classList.contains('tvKey')) {
    const firstKey = active.parentElement?.querySelector('.tvKey')
    if (firstKey && active.offsetTop === firstKey.offsetTop) {
      event.preventDefault()
      event.stopPropagation()
      searchInput.value?.focus()
    }
    return
  }

  if (event.key === 'ArrowDown') {
    if (active?.tagName === 'INPUT' && active.closest('.tvSearchOverlay') !== null) {
      const options = active.closest('.ft-input-component')?.querySelectorAll('.list li') ?? []
      if (options.length === 0 || options[options.length - 1].classList.contains('hover')) {
        // The key is left to carry on to the input, whose own handling of Down past the
        // last suggestion is to drop the highlight. Without that the input would go on
        // showing a suggestion that was never chosen. Focus moves once it has had it,
        // which means a timeout and not nextTick: a microtask runs between listeners, so
        // focus would already be on a key when spatial navigation sees this same press.
        setTimeout(() => keyboard.value?.focus(), 0)
      }
    }
  }
}

function exitPrompt() {
  emit('close')
}

onMounted(() => {
  lastActiveElement = document.activeElement
  // on window rather than document, so that it runs ahead of spatial navigation's own
  // capturing listener and can take a key before that moves focus
  window.addEventListener('keydown', handleEscape, true)
  store.commit('addOpenPrompt', id)
  if (process.env.IS_ANDROID) {
    android.enterPromptMode()
    // We draw our own keyboard — stop Android TV from layering its on-screen one on top.
    android.suppressInputMethod()
    window.addEventListener('exit-prompt', exitPrompt)
  }

  nextTick(() => {
    // Start on the keys rather than the input: typing is what the overlay is opened for,
    // and a focused input drops its suggestions down over the top row of the keyboard.
    // Up from the keys reaches the input, and the suggestions with it.
    keyboard.value?.focus()
    store.dispatch('showOutlines')
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleEscape, true)
  store.commit('removeOpenPrompt', id)
  if (process.env.IS_ANDROID) {
    android.restoreInputMethod()
    android.exitPromptMode()
    window.removeEventListener('exit-prompt', exitPrompt)
  }
  nextTick(() => lastActiveElement?.focus())
})
</script>

<style scoped>
.tvSearchOverlay {
  position: fixed;
  inset-block-start: 0;
  inset-inline-start: 0;
  inline-size: 100%;
  block-size: 100%;
  background-color: rgb(0 0 0 / 70%);
  z-index: 201;
  padding: 15px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  overflow-y: auto;
}

.tvSearchCard {
  inline-size: min(720px, 95%);
  max-block-size: none;
  overflow: visible;
  padding-block: 20px 16px;
  padding-inline: 20px;
  box-sizing: border-box;
  border-radius: 8px;
  background-color: var(--card-bg-color);
  box-shadow: 0 1px 2px rgb(0 0 0 / 10%);
}

.tvSearchTitle {
  text-align: center;
  margin-block: 0 16px;
  margin-inline: 0;
  font-size: 1.4em;
}

.tvSearchInputRow {
  display: flex;
  align-items: center;
  gap: 10px;
}

.tvSearchInput {
  flex: 1;
  min-inline-size: 0;
}

/* reuse TopNav filter button styling */
.tvSearchFilterButton {
  border-radius: 50%;
  border-style: none;
  background-color: transparent;
  color: var(--primary-text-color);
  cursor: pointer;
  font-size: 20px;
  line-height: 1em;
  padding: 10px;
  transition: background 0.2s ease-out;
  flex-shrink: 0;
}

.tvSearchFilterButton:hover {
  background-color: var(--side-nav-hover-color);
  color: var(--side-nav-hover-text-color);
}

.tvSearchFilterButton:active {
  background-color: var(--tertiary-text-color);
  color: var(--side-nav-active-text-color);
}

.tvSearchFilterButton.filterChanged {
  box-shadow: 0 0 20px var(--primary-color);
  color: var(--primary-color);
}

.tvSearchActions {
  display: flex;
  justify-content: center;
  margin-block-start: 16px;
}

.tvSearchCloseButton {
  padding: 10px 20px;
  border-radius: 4px;
  border: 1px solid var(--primary-color);
  background-color: transparent;
  color: var(--primary-text-color);
  cursor: pointer;
  font-size: 14px;
}

.tvSearchCloseButton:hover {
  background-color: var(--side-nav-hover-color);
}

.tvSearchCloseButton:active {
  background-color: var(--tertiary-text-color);
}

:deep(.ft-input-component .list) {
  /* ensure dropdown is not clipped by card and sits above overlay */
  z-index: 10;
}
</style>
