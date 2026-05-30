package com.example.skybeacon.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.skybeacon.MainActivity
import com.example.skybeacon.R
import com.example.skybeacon.data.AppDatabase
import com.example.skybeacon.data.BroadcastLog
import com.example.skybeacon.data.BroadcastMessage
import com.example.skybeacon.data.Offer
import kotlinx.coroutines.launch

class BroadcastManagementFragment : Fragment(R.layout.fragment_broadcast_management) {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(AppDatabase.getDatabase(requireContext()))
    }
    
    private var currentShopId: Int = -1
    private var shopName: String = "Shop"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentShopId = arguments?.getInt("shopId") ?: -1

        val tvTitle = view.findViewById<TextView>(R.id.tv_shop_title)
        
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.getDatabase(requireContext()).shopProfileDao()
            val shop = dao.getShopById(currentShopId)
            shop?.let {
                shopName = it.shopName
                tvTitle.text = it.shopName
            }
        }

        val rvOffers = view.findViewById<RecyclerView>(R.id.rv_offers)
        val rvMessages = view.findViewById<RecyclerView>(R.id.rv_messages)
        val btnAddOffer = view.findViewById<Button>(R.id.btn_add_offer)
        val btnAddMessage = view.findViewById<Button>(R.id.btn_add_message)
        val btnStop = view.findViewById<Button>(R.id.btn_stop_broadcast)

        val offerAdapter = OfferAdapter(
            onAdvertise = { offer ->
                val payload = "SB:${shopName.replace(" ", "")}|${offer.title} - ${offer.discountPercent}% OFF".take(53)
                (requireActivity() as MainActivity).triggerBroadcast(payload)
                viewModel.insertLog(BroadcastLog(shopName = shopName, broadcastType = "Offer", content = "${offer.title} - ${offer.discountPercent}% OFF"))
            },
            onDelete = { offer -> viewModel.deleteOffer(offer) }
        )
        
        val messageAdapter = MessageAdapter(
            onBroadcast = { msg ->
                val payload = "SB:${shopName.replace(" ", "")}|${msg.messageText}".take(53)
                (requireActivity() as MainActivity).triggerBroadcast(payload)
                viewModel.insertLog(BroadcastLog(shopName = shopName, broadcastType = "Message", content = msg.messageText))
            },
            onDelete = { msg -> viewModel.deleteMessage(msg) }
        )

        rvOffers.layoutManager = LinearLayoutManager(requireContext())
        rvOffers.adapter = offerAdapter
        
        rvMessages.layoutManager = LinearLayoutManager(requireContext())
        rvMessages.adapter = messageAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.getOffersForShop(currentShopId).collect { offers ->
                offerAdapter.submitList(offers)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.getMessagesForShop(currentShopId).collect { messages ->
                messageAdapter.submitList(messages)
            }
        }

        btnAddOffer.setOnClickListener { showAddOfferDialog() }
        btnAddMessage.setOnClickListener { showAddMessageDialog() }
        btnStop.setOnClickListener { (requireActivity() as MainActivity).haltBroadcast() }
    }

    private fun showAddOfferDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val titleInput = EditText(context).apply { hint = "Offer Title (e.g. Free Coffee)" }
        val discountInput = EditText(context).apply { 
            hint = "Discount % (e.g. 20)" 
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(titleInput)
        layout.addView(discountInput)

        AlertDialog.Builder(context)
            .setTitle("Add Offer")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val title = titleInput.text.toString()
                val discount = discountInput.text.toString().toIntOrNull() ?: 0
                if (title.isNotBlank()) {
                    viewModel.insertOffer(Offer(shopId = currentShopId, title = title, discountPercent = discount, validUntil = 0))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddMessageDialog() {
        val context = requireContext()
        val input = EditText(context).apply { hint = "Message Text" }
        
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(context)
            .setTitle("Add Message")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    viewModel.insertMessage(BroadcastMessage(shopId = currentShopId, messageText = text))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
