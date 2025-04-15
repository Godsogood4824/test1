import aiohttp
from telegram import Update
from telegram.ext import Application, CommandHandler, MessageHandler, filters, ContextTypes
from bs4 import BeautifulSoup
import uuid
import logging
import asyncio

# Thiết lập logging
logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

# Hàm kiểm tra ID Facebook và lấy thông tin công khai
async def check_facebook_id(fb_id: str, retries: int = 2) -> str:
    session_id = str(uuid.uuid4())
    for attempt in range(retries):
        try:
            async with aiohttp.ClientSession() as session:
                url = f"https://www.facebook.com/{fb_id}"
                headers = {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                    'Accept-Language': 'en-US,en;q=0.9',
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
                    'Connection': 'keep-alive',
                    'Upgrade-Insecure-Requests': '1',
                    'Sec-Fetch-Mode': 'navigate',
                    'Sec-Fetch-Site': 'none',
                    'Sec-Fetch-User': '?1'
                }
                logger.info(f"Session {session_id}: Attempt {attempt + 1} checking {url}")
                async with session.get(url, headers=headers, timeout=20) as response:
                    text = await response.text()
                    logger.info(f"Session {session_id}: Status {response.status}, Response snippet: {text[:200]}")
                    if response.status == 200:
                        # Phân tích HTML để lấy thông tin công khai
                        soup = BeautifulSoup(text, 'html.parser')
                        
                        # Kiểm tra lỗi trang không tồn tại
                        if any(phrase in text for phrase in ["This content isn't available", "Page Not Found", "Sorry, something went wrong"]):
                            return f"ID {fb_id}: Tài khoản không tồn tại hoặc đã bị vô hiệu hóa."
                        
                        # Lấy thông tin công khai
                        info = {"ID": fb_id, "Status": "Tài khoản còn sống"}
                        
                        # Tên hiển thị
                        title_tag = soup.find('title')
                        if title_tag and title_tag.text != "Facebook":
                            info["Name"] = title_tag.text.strip()
                        
                        # Ảnh đại diện
                        img_tag = soup.find('meta', property='og:image')
                        if img_tag and img_tag.get('content'):
                            info["Profile Picture"] = img_tag['content']
                        
                        # Thông tin giới thiệu
                        intro_tags = soup.find_all('div', class_=lambda x: x and 'intro' in x.lower())
                        intro_text = []
                        for tag in intro_tags:
                            text = tag.get_text(strip=True)
                            if text:
                                intro_text.append(text)
                        if intro_text:
                            info["Intro"] = " | ".join(intro_text[:2])
                        
                        # Bài đăng ghim
                        pinned_post = soup.find('div', class_=lambda x: x and 'pinned' in x.lower())
                        if pinned_post:
                            pinned_text = pinned_post.get_text(strip=True)[:200]  # Giới hạn độ dài
                            if pinned_text:
                                info["Pinned Post"] = pinned_text
                        
                        # Trạng thái mới nhất (thử lấy bài đăng đầu tiên)
                        post = soup.find('div', class_=lambda x: x and 'userContent' in x.lower())
                        if post:
                            post_text = post.get_text(strip=True)[:200]
                            if post_text:
                                info["Latest Status"] = post_text
                        
                        # Nhạc ghim (rất khó, thử tìm meta hoặc thẻ liên quan)
                        music_tag = soup.find('meta', property='music:song')
                        if music_tag and music_tag.get('content'):
                            info["Pinned Music"] = music_tag['content']
                        
                        # Định dạng kết quả
                        result = f"ID {fb_id}: Tài khoản còn sống.\n"
                        for key, value in info.items():
                            if key != "Status":
                                result += f"{key}: {value}\n"
                        if not any(k in info for k in ["Pinned Post", "Latest Status", "Pinned Music"]):
                            result += "Không tìm thấy bài đăng ghim, trạng thái, hoặc nhạc công khai.\n"
                        return result.strip()
                    
                    elif response.status == 404:
                        return f"ID {fb_id}: Tài khoản không tồn tại."
                    elif response.status == 403:
                        return f"ID {fb_id}: Bị chặn bởi Facebook (mã lỗi: 403)."
                    elif response.status == 400:
                        return f"ID {fb_id}: Yêu cầu không hợp lệ (mã lỗi: 400). Có thể ID không đúng hoặc bị hạn chế."
                    else:
                        return f"ID {fb_id}: Không thể kiểm tra (mã lỗi: {response.status})."
        except aiohttp.ClientError as e:
            logger.error(f"Session {session_id}: Error checking {fb_id} - {str(e)}")
            if attempt < retries - 1:
                logger.info(f"Session {session_id}: Retrying after 5 seconds...")
                await asyncio.sleep(5)
                continue
            return f"ID {fb_id}: Lỗi khi kiểm tra - {str(e)}"
        finally:
            await asyncio.sleep(3)
    return f"ID {fb_id}: Không thể kiểm tra sau {retries} lần thử."

# Hàm xử lý lệnh /start
async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_text(
        "👋 Chào bạn! Tôi là bot kiểm tra trạng thái ID Facebook.\n"
        "Gửi ID hoặc tên người dùng (ví dụ: 'zuck' hoặc '100000123456789').\n"
        "Tôi sẽ kiểm tra trạng thái và thông tin công khai (nếu có), như tên, ảnh, bài ghim, hoặc trạng thái.\n"
        "Dùng /help để biết thêm chi tiết."
    )

# Hàm xử lý lệnh /help
async def help_command(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_text(
        "📖 Hướng dẫn:\n"
        "- Gửi ID Facebook hoặc tên người dùng để kiểm tra.\n"
        "- Tôi sẽ báo trạng thái tài khoản và thông tin công khai (nếu có), như tên, ảnh đại diện, bài ghim, trạng thái, hoặc nhạc.\n"
        "- Ví dụ: 'zuck' hoặc '100000123456789'.\n"
        "- Lưu ý: Tài khoản riêng tư có thể không hiển thị thông tin chi tiết."
    )

# Hàm xử lý tin nhắn
async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    fb_id = update.message.text.strip()
    if not fb_id:
        await update.message.reply_text("❌ Vui lòng gửi ID Facebook hợp lệ.")
        return
    
    result = await check_facebook_id(fb_id)
    await update.message.reply_text(result)

def main() -> None:
    # Thay bằng token của bạn
    TOKEN = "7721420080:AAFV7vhPMojQWYeTBCfVAPuxpqYW_09Ym2U"
    
    # Khởi tạo bot
    application = Application.builder().token(TOKEN).build()
    
    # Đăng ký lệnh
    application.add_handler(CommandHandler("start", start))
    application.add_handler(CommandHandler("help", help_command))
    application.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    
    # Chạy bot
    logger.info("Bot đang khởi động...")
    application.run_polling(allowed_updates=Update.ALL_TYPES)

if __name__ == "__main__":
    main()