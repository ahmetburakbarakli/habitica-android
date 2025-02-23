package com.habitrpg.android.habitica.ui.fragments.social.guilds

import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.habitrpg.android.habitica.R
import com.habitrpg.android.habitica.components.UserComponent
import com.habitrpg.android.habitica.data.SocialRepository
import com.habitrpg.android.habitica.databinding.FragmentRefreshRecyclerviewBinding
import com.habitrpg.android.habitica.helpers.RxErrorHandler
import com.habitrpg.android.habitica.ui.adapter.social.GuildListAdapter
import com.habitrpg.android.habitica.ui.fragments.BaseFragment
import com.habitrpg.android.habitica.ui.helpers.EmptyItem
import com.habitrpg.android.habitica.ui.helpers.KeyboardUtil
import com.habitrpg.android.habitica.ui.helpers.SafeDefaultItemAnimator
import javax.inject.Inject

class GuildListFragment : BaseFragment<FragmentRefreshRecyclerviewBinding>(), SearchView.OnQueryTextListener, SearchView.OnCloseListener, SwipeRefreshLayout.OnRefreshListener {

    @Inject
    lateinit var socialRepository: SocialRepository

    override var binding: FragmentRefreshRecyclerviewBinding? = null
    var onlyShowUsersGuilds: Boolean = false

    override fun createBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRefreshRecyclerviewBinding {
        return FragmentRefreshRecyclerviewBinding.inflate(inflater, container, false)
    }

    private var viewAdapter = GuildListAdapter()

    override fun injectFragment(component: UserComponent) {
        component.inject(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding?.recyclerView?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
        viewAdapter.socialRepository = socialRepository
        binding?.recyclerView?.adapter = viewAdapter
        binding?.recyclerView?.itemAnimator = SafeDefaultItemAnimator()
        binding?.recyclerView?.emptyItem = EmptyItem(
            getString(R.string.empty_guilds_list),
            getString(R.string.empty_discover_description)
        )

        binding?.refreshLayout?.setOnRefreshListener(this)

        viewAdapter.onlyShowUsersGuilds = onlyShowUsersGuilds
        if (onlyShowUsersGuilds) {
            compositeSubscription.add(socialRepository.getUserGroups("guild").subscribe({ viewAdapter.setUnfilteredData(it) }, RxErrorHandler.handleEmptyError()))
        } else {
            compositeSubscription.add(
                this.socialRepository.getPublicGuilds()
                    .subscribe(
                        { groups ->
                            this@GuildListFragment.viewAdapter.setUnfilteredData(groups)
                        },
                        RxErrorHandler.handleEmptyError()
                    )
            )
        }
        this.fetchGuilds()
    }

    override fun onDestroy() {
        socialRepository.close()
        super.onDestroy()
    }

    internal fun fetchGuilds() {
        compositeSubscription.add(
            this.socialRepository.retrieveGroups(if (onlyShowUsersGuilds) "guilds" else "publicGuilds")
                .subscribe(
                    {
                        binding?.refreshLayout?.isRefreshing = false
                    },
                    RxErrorHandler.handleEmptyError()
                )
        )
    }

    override fun onQueryTextSubmit(s: String?): Boolean {
        viewAdapter.filter.filter(s)
        activity?.let {
            KeyboardUtil.dismissKeyboard(it)
        }
        return true
    }

    override fun onQueryTextChange(s: String?): Boolean {
        viewAdapter.filter.filter(s)
        return true
    }

    override fun onRefresh() {
        fetchGuilds()
    }

    override fun onClose(): Boolean {
        viewAdapter.filter.filter("")
        return false
    }
}
